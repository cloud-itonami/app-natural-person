(ns natural-person.route-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [natural-person.route :as route]
            [natural-person.view :as view]))

(deftest dispatch-page-and-health
  (is (= :page (:action (route/dispatch "GET" "/"))))
  (is (= :health (:action (route/dispatch "GET" "/health"))))
  (is (= :method-not-allowed (:action (route/dispatch "POST" "/health"))))
  (is (= :method-not-allowed (:action (route/dispatch "POST" "/"))))
  (testing "移行前に 404 だったものは 404 のまま（src/app.ts の /healthz /readyz /_app/meta は deploy されていなかった）"
    (is (= :not-found (:action (route/dispatch "GET" "/healthz"))))
    (is (= :not-found (:action (route/dispatch "GET" "/readyz"))))
    (is (= :not-found (:action (route/dispatch "GET" "/_app/meta"))))
    (is (= :not-found (:action (route/dispatch "GET" "/nope"))))))

(deftest dispatch-xrpc
  (testing "単一セグメントの nsid"
    (is (= {:action :xrpc :nsid "com.etzhayyim.apps.naturalPerson.getPerson"}
           (route/dispatch "POST" "/xrpc/com.etzhayyim.apps.naturalPerson.getPerson"))))
  (testing "空だけが 400。多段は移行前の [...path] と同じく転送する（絞るのは方針変更）"
    (is (= :bad-request (:action (route/dispatch "POST" "/xrpc/"))))
    (is (= {:action :xrpc :nsid "a/b"} (route/dispatch "POST" "/xrpc/a/b"))))
  (testing "namespace 検査は無い —— 移行前の配備 handler も無かった（README S1 の実測）"
    (is (= :xrpc (:action (route/dispatch "POST" "/xrpc/com.example.totallyUnrelated.doAnything")))))
  (testing "preflight と method"
    (is (= :cors-preflight (:action (route/dispatch "OPTIONS" "/xrpc/x"))))
    (is (= :method-not-allowed (:action (route/dispatch "GET" "/xrpc/x"))))))

(deftest mcp-url-resolution
  (is (= "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"
         (route/mcp-router-url {})))
  (is (= "https://a.example/x" (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "https://a.example/x/"})))
  (testing "空白だけの設定は未設定として扱う（移行前の +server.ts と同じ）"
    (is (= "https://b.example" (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "   "
                                                     :MCP_ROUTER_URL "https://b.example"})))))

(deftest unwrap
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:structuredContent {:a 1}}})))
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:a 1}})))
  (is (false? (:ok? (route/unwrap-mcp {:error {:message "boom"}})))))

(deftest page-shows-the-real-routes
  (testing "ページは route 表から描く。0 を焼かない（docs/adr/0001 の欠陥 E）"
    (let [html (view/render {:css "/*x*/" :routes route/routes
                             :vars [:APP_NANOID :APP_UI_TYPE]
                             :mcp-url "https://mcp.example/x"})]
      (doseq [r route/routes]
        (is (str/includes? html (:route/path r))
            (str (:route/path r) " がページに出ていない")))
      (is (str/includes? html "APP_NANOID"))
      (is (str/includes? html "https://mcp.example/x"))
      (is (not (str/includes? html "No public route is declared")))
      (is (not (str/includes? html "No public vars are declared"))))))
