(ns natural-person.view
  "この appview の説明ページ。純 hiccup。

  基盤は `jp-go-dds`(デジタル庁デザインシステム) —— superproject の skill
  `kotoba-uiux` が定める新規 UI の base。色・寸法は `--hig-*` トークン契約で
  書き、raw hex も px フォントサイズも置かない。

  **表示する事実は引数で受け取る。ページの中に焼かない。**
  これは装飾の都合ではなく、docs/adr/0001 が記録した欠陥そのものへの答えで
  ある —— 移行前のページは `routeCount: 0` と `vars: []` を literal で持って
  いて、隣の wrangler.jsonc が route 2・var 8 を宣言していることに気づけな
  かった。ここでは route 表と設定を渡す側が持ち、ページは描くだけなので、
  両者がずれる余地が無い。"
  (:require [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [kotoba.lang.text :as str]))

(def app-css
  "app 固有の最小 CSS。`--hig-*` 契約だけを使う(bridge が DADS の上に再定義する)。
  DADS を base にした app の下には `shitsuke.hig` が居ないので、bridge が運んで
  いないトークンは何にも解決しない —— 使うのは運ばれている 71 個の中だけ。"
  (str/join
   "\n"
   [".np-lede { color: var(--hig-color-secondary-label); max-width: 42rem; }"
    ".np-note { color: var(--hig-color-secondary-label); font-size: var(--hig-text-footnote-font-size); }"
    ".np-mono { font-family: var(--hig-font-mono); }"]))

(defn- route-rows [routes]
  (mapv (fn [r]
          [(str/upper (name (:route/method r)))
           [:span {:class "np-mono"} (:route/path r)]
           (:route/doc r)])
        routes))

(defn body
  "opts:
   :routes    natural-person.route/routes（この Worker が実際に答えるもの）
   :vars      wrangler が渡した env のキー（**キー名だけ**。値は出さない）
   :mcp-url   XRPC の中継先（route/mcp-router-url の戻り値）
   :built-at  bundle のビルド時刻（不明なら nil）"
  [{:keys [routes vars mcp-url built-at]}]
  (dds/container
   (dds/section
    {}
    (dds/heading 1 "Natural Person — 自然人 appview")
    [:p {:class "np-lede"}
     "統計ベースの人口コホート生成・人物同定・法域別 compliance 評価の"
     [:strong "公開面"]
     "。判断そのものは etzhayyim/root 側の BPMN 契約と MCP router にあり、"
     "ここには無い —— この面が持つのは、到着した XRPC 要求を router へ渡す"
     "ことだけである。"])

   (dds/section
    {:title "この面が答えるもの"}
    (dds/table {:caption "公開ルート"
                :headers ["METHOD" "PATH" "何をするか"]
                :rows (route-rows routes)})
    [:p {:class "np-note"}
     "この表は Worker の route 表そのものから描いている。ページに焼いた値では"
     "ないので、実際に答えるものと表示がずれない。"])

   (dds/section
    {:title "実行時の設定"}
    (if (seq vars)
      [:div (into [:p] (interpose " "
                                  (map (fn [k] (dds/chip-label (name k))) vars)))
       [:p {:class "np-note"}
        "キー名のみ。"
        [:strong "ただし下の中継先だけは値そのもの"]
        "（"
        [:span {:class "np-mono"} "AGENTGATEWAY_MCP_ROUTER_URL"]
        "）—— どこへ中継するかは運用者が見る必要があるので意図的に出している。"
        "それ以外の値は出さない。"]]
      [:p {:class "np-note"} "env が渡されていない（ローカル描画）。"])
    [:p {:class "np-note"} "XRPC の中継先: "
     [:span {:class "np-mono"} mcp-url]])

   (dds/section
    {:title "現在地"}
    [:p {:class "np-lede"}
     "この appview は TypeScript/Svelte から ClojureScript へ移行済み。"
     "deploy される bundle は、いま読んでいるソースからコンパイルされたもので"
     "ある（docs/adr/0001）。"]
    (when built-at
      [:p {:class "np-note"} "bundle build: " built-at]))))

(defn render
  "完全な HTML 文書。`css` は呼び出し側が渡す(ライブラリは I/O を持たない)。"
  [{:keys [css] :as opts}]
  (page/->page
   {:title "Natural Person — 自然人 appview"
    :description "統計ベースの人口コホート管理 appview の公開面。XRPC を MCP router へ中継する。"
    :lang "ja"
    :css css
    :app-css (str tokens/bridge-css "\n" app-css)}
   (body opts)))
