#!/usr/bin/env nbb
;; verify-docs-claims — README.md と docs/operator-quickstart.md が述べる数値・
;; 存在・不在を tree から再計算し、prose と tree が食い違ったら落ちる。
;;
;; cljs 移行の前、このファイルが担うべき主張は **ギャップ** だった: deploy される
;; Worker は SvelteKit のビルド出力で、application のように読める src/app.ts は
;; どの bundle にも入っていなかった。そのギャップは閉じたので、claim は
;; **閉じたこと** を主張する。しかも黙って戻れないように書く —— TypeScript は
;; 「バイト合計に現れない」ではなく **名指しで不在** を検査する。
;;
;; Usage:  nbb scripts/verify-docs-claims.cljs [<dir>]     (<dir> を **先頭** に。既定 ".")
;;   多くの gate が (first (remove #(str/starts-with? % "--") argv)) で tree を
;;   決めるので、`--flag <dir>` の順で書くとフラグの値が tree のパスになる。
;; Exit:   0 全 claim が成立 · 1 claim が偽 · 2 答えられなかった

(require '["node:fs" :as fs]
         '["node:child_process" :as cp]
         '["node:crypto" :as crypto]
         '[cljs.reader :as reader]
         '[kotoba.lang.text :as str])

(def root (or (first (remove #(str/starts-with? % "--") *command-line-args*)) "."))
(def APP "appview/etzhayyim-wasm-natural-person-np02priv9")

(def claims
  {:tracked-files 18
   :inherited-bytes 3358            ; 継承した 4 ファイルを 1 バイトも変えずに持っている
   :svelte-artifacts 0              ; .svelte / svelte.config / svelte/ ディレクトリ が 1 つも無い
   :sveltekit-compat-flags 0        ; nodejs_compat / nodejs_als は adapter-cloudflare のものだった
   :production-ts-files 0
   :production-canonical-files 4
   :declared-vars 8
   :declared-routes 2
   :wrangler-main "../../dist/worker.js"
   :shadow-output-dir "dist"
   :shadow-export "natural-person.worker/handler"})

;; 移行が **1 バイトも変えていない** 継承ファイル。wrangler.jsonc と CLAUDE.md は
;; 意図的に変更したのでこの集合から外し、代わりに **内容で** 検査する（意図的な
;; 変更と勝手な変更を区別するため）。
(def preserved
  {"NOTICE" "52220ea827414c22ee649ca4cb686fc55ca0cfed8fc39b0c9484e4b262bdf3f7"
   "README.edn" "993746f27ebbd97c8107383699e2a96c82ebb2e0b32a33289d70b05d208e2d03"
   "migration.edn" "afe712240e3502d8408d9aa19dbd79df048420f99bcfb3b2cf99ba2b9b07531e"
   "appview/etzhayyim-wasm-natural-person-np02priv9/kotodama.jsonld"
   "2682295d3c7a429f7d71badeb77c4fdcc134ca562644a884db60c5b9cabd810d"})

;; 移行が **撤去した** もの、名指しで。バイト合計は「TypeScript が消えた」と
;; 言えない。これは言えるし、どれか 1 つでも戻ってきたら落ちる。
(def removed-by-migration
  ["appview/etzhayyim-wasm-natural-person-np02priv9/src/app.ts"
   "appview/etzhayyim-wasm-natural-person-np02priv9/svelte/package.json"
   "appview/etzhayyim-wasm-natural-person-np02priv9/svelte/src/app.html"
   "appview/etzhayyim-wasm-natural-person-np02priv9/svelte/src/routes/+page.svelte"
   "appview/etzhayyim-wasm-natural-person-np02priv9/svelte/src/routes/xrpc/[...path]/+server.ts"
   "appview/etzhayyim-wasm-natural-person-np02priv9/svelte/svelte.config.js"
   "appview/etzhayyim-wasm-natural-person-np02priv9/svelte/tsconfig.json"
   "appview/etzhayyim-wasm-natural-person-np02priv9/svelte/vite.config.ts"])

(def undetermined (atom []))
(def failures (atom []))
(defn undet! [m] (swap! undetermined conj m))

(defn tracked-files []
  (try (->> (.execSync cp "git ls-files" #js {:cwd root :encoding "utf8"})
            str/split-lines (remove str/blank?) vec)
       (catch :default e (undet! (str "git ls-files failed: " (.-message e))) nil)))
(defn slurp* [rel] (try (.readFileSync fs (str root "/" rel) "utf8") (catch :default _ nil)))
(defn bytes-of [rel] (try (.-size (.statSync fs (str root "/" rel))) (catch :default _ nil)))
(defn sha256 [rel]
  (try (-> (.createHash crypto "sha256") (.update (.readFileSync fs (str root "/" rel))) (.digest "hex"))
       (catch :default _ nil)))
(defn strip-jsonc [s] (str/replace s #"(?m)^\s*//.*$" ""))

(defn check! [label expected actual]
  (let [ok (= expected actual)]
    (println (str (if ok "PASS" "FAIL") "\t" (name label)
                  "\texpected=" (pr-str expected) "\tactual=" (pr-str actual)))
    (when-not ok (swap! failures conj label))
    ok))

(let [files (tracked-files)]
  (when (nil? files) (println "UNDETERMINED\tcould not list tracked files") (js/process.exit 2))
  ;; evidence floor: 0 件を clean と数えない
  (println (str "SCANNED\t" (count files)))
  (when (zero? (count files)) (println "UNDETERMINED\tscanned 0 files") (js/process.exit 2))

  (let [sizes (into {} (map (juxt identity bytes-of)) files)]
    (when-let [bad (seq (keep (fn [[f s]] (when (nil? s) f)) sizes))]
      (undet! (str "tracked but unreadable: " (str/join ", " bad))))

    (check! :tracked-files (:tracked-files claims) (count files))
    (check! :inherited-bytes (:inherited-bytes claims)
            (reduce + 0 (keep #(get sizes %) (keys preserved))))
    (check! :preserved-files-unchanged []
            (vec (keep (fn [[f want]] (let [got (sha256 f)]
                                        (when-not (= want got) (str f " " (or got "MISSING")))))
                       preserved)))

    ;; TypeScript は消えた、名指しで
    (check! :removed-by-migration-absent []
            (vec (filter #(some? (bytes-of %)) removed-by-migration)))

    ;; Svelte は消えた、そして戻ってきてはいけない。removed-by-migration は
    ;; 8 パスを名指しするが、これは **どんな名前でも** 戻りを捕まえる ——
    ;; 新しい .svelte ファイル、svelte.config、svelte/ ディレクトリ。
    (check! :svelte-artifacts (:svelte-artifacts claims)
            (count (filter #(or (str/ends-with? % ".svelte")
                                (str/includes? % "svelte.config")
                                (str/includes? % "/svelte/"))
                           files)))

    ;; CLAUDE.md はもう Svelte frontend を計画せず、runtime を cljs と述べる
    (let [c (slurp* "CLAUDE.md")]
      (if (nil? c)
        (undet! "CLAUDE.md unreadable")
        (check! :claude-md-describes-cljs true
                (and (str/includes? c "shadow-cljs")
                     (str/includes? c "ClojureScript")
                     (str/includes? c "Svelte は使わない")))))

    ;; production source の言語
    (let [prod (remove #(str/starts-with? % "scripts/") files)]
      (check! :production-ts-files (:production-ts-files claims)
              (count (filter #(str/ends-with? % ".ts") prod)))
      (check! :production-canonical-files (:production-canonical-files claims)
              (count (filter #(re-find #"\.(cljs|cljc|clj|kotoba)$" %) prod))))

    ;; deploy される bundle は、この tree のソースからビルドされる
    (let [w (some-> (slurp* (str APP "/wrangler.jsonc")) strip-jsonc)
          sh (slurp* "shadow-cljs.edn")]
      (if (or (nil? w) (nil? sh))
        (undet! "wrangler.jsonc or shadow-cljs.edn unreadable")
        (let [j (js->clj (.parse js/JSON w) :keywordize-keys false)
              ;; shadow-cljs.edn は **EDN として読む**。grep で見ると自分の
              ;; コメント（":build-options ではなく" と書いてある）に当たって
              ;; しまい、置き場所の検査が落ちなくなる —— これはこの option が
              ;; 防ぐはずの失敗そのものである。
              shadow (try (reader/read-string sh) (catch :default e (undet! (str "shadow-cljs.edn unreadable as EDN: " (.-message e))) nil))
              copts (get-in shadow [:builds :worker :compiler-options])
              bopts (get-in shadow [:builds :worker :build-options])]
          (check! :wrangler-main (:wrangler-main claims) (get j "main"))
          (check! :declared-vars (:declared-vars claims) (count (get j "vars")))
          (check! :declared-routes (:declared-routes claims) (count (get j "routes")))
          (check! :app-framework-not-sveltekit true
                  (not (str/includes? (str/lower (str (get-in j ["vars" "APP_FRAMEWORK"]))) "svelte")))
          ;; 旧設定は今は存在しない SvelteKit の client dir を配っていた
          (check! :no-stale-assets-binding true (nil? (get j "assets")))
          (check! :sveltekit-compat-flags (:sveltekit-compat-flags claims)
                  (count (filter #{"nodejs_compat" "nodejs_als"}
                                 (or (get j "compatibility_flags") []))))
          (check! :shadow-builds-that-main true
                  (and (= "dist" (get-in shadow [:builds :worker :output-dir]))
                       (= 'natural-person.worker/handler
                          (get-in shadow [:builds :worker :modules :worker :exports 'default]))
                       (str/includes? (str (get j "main"))
                                      (str (:shadow-output-dir claims) "/worker.js"))))
          ;; **EDN を parse して** 置き場所を確かめる。grep ではない。
          (check! :warnings-as-errors-in-compiler-options true (true? (:warnings-as-errors copts)))
          (check! :warnings-as-errors-not-misplaced true (nil? (:warnings-as-errors bopts))))))

    ;; ページは route **表** を描く。焼いた数ではない —— ADR-0001 が記録した
    ;; 欠陥は literal な `routeCount: 0` が route 2 を宣言する設定の隣に在った
    ;; ことだった。構造で検査し、**部分文字列の禁止では検査しない**: 禁止形は
    ;; 旧欠陥を説明する docstring に当たってしまう。コメントで落ちる検査は
    ;; コードではなく散文についての検査である。
    (let [v (slurp* "src/natural_person/view.cljc")
          w (slurp* "src/natural_person/worker.cljs")]
      (if (or (nil? v) (nil? w))
        (undet! "view.cljc or worker.cljs unreadable")
        (check! :page-renders-route-table true
                (and (str/includes? v "[{:keys [routes vars mcp-url built-at]}]")
                     (str/includes? v "(route-rows routes)")
                     (str/includes? w ":routes route/routes")))))

    ;; ADR は cljs.reader で読める EDN tx-data である
    (let [adr "docs/adr/0001-migrate-the-appview-from-typescript-to-clojurescript.edn"
          s (slurp* adr)]
      (if (nil? s)
        (undet! (str adr " unreadable"))
        (let [d (try (reader/read-string s) (catch :default e (undet! (str adr " not readable EDN: " (.-message e))) nil))]
          (when d
            (check! :adr-is-tx-data true
                    (and (vector? d) (= 1 (count d))
                         (= "app-natural-person-0001" (:adr/id (first d)))
                         (= "accepted" (:adr/status (first d)))))))))))

(let [u @undetermined f @failures]
  (when (seq u)
    (doseq [m u] (println (str "UNDETERMINED\t" m)))
    (println "Refusing to report a pass: the tree could not be read completely.")
    (js/process.exit 2))
  (if (seq f)
    (do (println (str "FAILED\t" (count f) " claim(s): " (str/join ", " (map name f)))) (js/process.exit 1))
    (do (println "OK\tevery claim in README.md and docs/operator-quickstart.md holds") (js/process.exit 0))))
