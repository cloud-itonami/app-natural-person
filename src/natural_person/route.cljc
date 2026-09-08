(ns natural-person.route
  "どのハンドラが要求に答えるか —— データとして持ち、純関数で決める。

  `.cljs` ではなく `.cljc` なのは意図的である。edge worker のうち検査する価値が
  あるのは routing であり、ここならブラウザもビルドもネットワークも無しに検査
  できる。`natural-person.worker` が Request/Response に触る唯一の名前空間で、
  そこはこのファイルが既に決めたことしかしない。

  ingress capability が qualify した時（今日は `:native-aot` / `:wasm-aot` とも
  pending —— ADR-2606290000）に最初に `.kotoba` へ移るのもここである。route 表は
  スカラと文字列に対する判断であり、それはちょうどその移行を生き延びる形をして
  いる。"
  (:require [kotoba.lang.text :as str]))

(def routes
  "公開面をデータとして持つ。ページは**この値を**描くので、実際に在る route と
  ページが宣伝する route がずれる余地が無い。

  移行前のページはここを literal で持っており（`routeCount: 0` / `routes: []`）、
  隣の `wrangler.jsonc` が route 2・var 8 を宣言していることに気づけなかった
  （docs/adr/0001）。"
  [{:route/path "/"           :route/method :get  :route/kind :page
    :route/doc "この appview の説明ページ"}
   {:route/path "/health"     :route/method :get  :route/kind :json
    :route/doc "生存確認。deploy された面が答えることを外から確かめられる"}
   {:route/path "/xrpc/:nsid" :route/method :post :route/kind :proxy
    :route/doc "XRPC を MCP router へ中継する"}])

(defn- xrpc-nsid
  "`/xrpc/<nsid>` の nsid。**空文字だけが nil**。

  多段パス（`/xrpc/a/b`）も通す。移行前の SvelteKit route は rest parameter
  `[...path]` で受けており、`a/b` をそのまま tool 名として転送していた（実測:
  `+server.ts` の `event.params.path` は `if (!nsid)` だけを 400 にする）。
  ここで 1 セグメントに絞ると挙動が変わる —— NSID に `/` は現れないので上流で
  失敗するだけだが、**それは移行ではなく方針変更**であり、移行の commit に
  紛れ込ませるべきものではない。絞るなら別の決定として記録する。"
  [path]
  (when (str/starts-with? path "/xrpc/")
    (let [rest' (subs path (count "/xrpc/"))]
      (when (seq rest') rest'))))

(defn dispatch
  "method + path → 何をするか。Request も Response も知らない。

  返すのは `{:action …}` で、`:action` は
  `:page` / `:health` / `:xrpc` / `:cors-preflight` / `:not-found` /
  `:method-not-allowed` / `:bad-request` のいずれか。"
  [method path]
  (let [m (keyword (str/lower (or method "get")))
        p (or path "")]
    (cond
      (and (= m :options) (str/starts-with? p "/xrpc/"))
      {:action :cors-preflight}

      (str/starts-with? p "/xrpc/")
      (if (= m :post)
        (if-let [nsid (xrpc-nsid p)]
          {:action :xrpc :nsid nsid}
          {:action :bad-request :reason "Missing XRPC method"})
        {:action :method-not-allowed :allow "POST, OPTIONS"})

      (= p "/health") (if (= m :get)
                        {:action :health}
                        {:action :method-not-allowed :allow "GET"})
      (= p "/")       (if (= m :get)
                        {:action :page}
                        {:action :method-not-allowed :allow "GET"})
      :else {:action :not-found})))

(defn mcp-router-url
  "env の設定 → MCP router の URL。末尾スラッシュは落とす。

  移行前の `+server.ts` と同じ解決順（`AGENTGATEWAY_MCP_ROUTER_URL` →
  `MCP_ROUTER_URL` → 既定値）で、空白だけの設定は未設定として扱うところまで
  同じ。既定値をここに焼くのは、**どこへ行くのかを 1 箇所で読めるようにする**
  ためである。"
  [{:keys [AGENTGATEWAY_MCP_ROUTER_URL MCP_ROUTER_URL]}]
  (let [pick (fn [s] (when (and (string? s) (seq (str/trim s))) (str/trim s)))]
    (-> (or (pick AGENTGATEWAY_MCP_ROUTER_URL)
            (pick MCP_ROUTER_URL)
            "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message")
        (str/replace #"/+$" ""))))

(def ^:private drop-headers
  "上流へ渡さない header。

  `host` —— 移行前の SvelteKit route も削っていた（宛先が変わるので嘘になる）。
  `content-length` / `content-encoding` —— body を JSON-RPC の封筒に詰め直す
  ので、元の長さもエンコーディングも当てはまらない。

  **それ以外は全部渡す。** 移行前は `new Headers(request.headers)` から host を
  削るだけで、`authorization` も上流に届いていた。移行で 3 つの header を新規に
  作る形にしたとき、それが黙って消えていた —— しかも preflight は
  `access-control-allow-headers: content-type,authorization` と許可を宣言した
  ままだったので、ブラウザには送ってよいと言いながら捨てていたことになる。"
  #{"host" "content-length" "content-encoding"})

(defn relay-headers
  "受け取った header を、上流へ渡す形にする。`in` は [[k v] …] の列。

  ここが `.cljc` にあるのは、これがビルドもブラウザも無しに固定できる**判断**
  だからである。`js/Headers` を worker 側で組み立てる形にすると、何が渡って
  何が落ちるかを述べたテストが書けない —— そしてこの欠陥は、まさに誰も
  『何が転送されるか』を訊かなかったから 21 repo で生き延びた。"
  [in nsid]
  (into {"content-type" "application/json"
         "x-etzhayyim-bff" "cljs-worker"
         "x-etzhayyim-xrpc-method" nsid}
        (comp (remove (fn [[k _]] (contains? drop-headers (str/lower k))))
              (map (fn [[k v]] [(str/lower k) v])))
        in))

(defn unwrap-mcp
  "MCP router の応答から、呼び手に返す値を取り出す。

  `{:result {:structuredContent X}}` → X、`{:result X}` → X、それ以外は素通し。
  `{:error …}` は呼び出し側が 502 にするので、ここでは判定だけ返す。移行前の
  `+server.ts` と同じ剥がし方である。"
  [payload]
  (cond
    (and (map? payload) (contains? payload :error))
    {:ok? false :error (get-in payload [:error :message] "MCP router returned an error")
     :upstream payload}

    (and (map? payload) (contains? payload :result))
    (let [r (:result payload)]
      {:ok? true :value (if (and (map? r) (contains? r :structuredContent))
                          (:structuredContent r)
                          r)})

    :else {:ok? true :value payload}))
