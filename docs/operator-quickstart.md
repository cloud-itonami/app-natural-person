# operator quickstart — app-natural-person

**この repo で今日実際にできることを、踏める形で上から書く。** 所要 5 分。
Cloudflare のアカウントは要らない（deploy だけが要る。§6）。

**掲載している出力はすべて 2026-08-19 に実際に walk した結果である。**

読む前に `README.md` を見ること —— この appview は 2026-08-19 に TypeScript/Svelte から
ClojureScript へ移行した。`src/app.ts` も `svelte/` も**もう無い**。

実測環境: macOS (darwin 25.3.0) / git **2.51.0** / node **v26.3.0** / npm **11.16.0** /
nbb **v1.4.210** / Clojure CLI **1.12.5.1654**。

## 0. 場所

```bash
git clone git@github.com:cloud-itonami/app-natural-person.git
cd app-natural-person
REPO=$PWD
```

配備単位は 1 段下（`appview/etzhayyim-wasm-natural-person-np02priv9/`）だが、**ビルドは
repo 直下で行う** —— `shadow-cljs.edn` と `deps.edn` はここに在り、`wrangler.jsonc` の
`main` は `../../dist/worker.js` を指す。移行前のように `svelte/` の下で `npm install`
する手順は**もう無い**。

## 1. 書いてあることが本当か検査する

```bash
npx --yes nbb scripts/verify-docs-claims.cljs .
```

実際の出力（末尾）:

```
SCANNED	18
PASS	tracked-files	expected=18	actual=18
PASS	inherited-bytes	expected=3358	actual=3358
PASS	removed-by-migration-absent	expected=[]	actual=[]
PASS	svelte-artifacts	expected=0	actual=0
PASS	production-ts-files	expected=0	actual=0
PASS	production-canonical-files	expected=4	actual=4
PASS	warnings-as-errors-in-compiler-options	expected=true	actual=true
...
OK	every claim in README.md and docs/operator-quickstart.md holds
```

19 claim すべて PASS、exit 0。**exit 2（UNDETERMINED）は 0 ではない** —— tree を読み切れ
なかったという別の答えで、「検査して問題なし」と混ぜない。

**`<dir>` は引数の先頭に置く。** この gate は
`(first (remove #(str/starts-with? % "--") argv))` で tree を決めるので、
`--flag <dir>` の順で書くとフラグの値が tree のパスになる。

この検査には移行の不変条件が入っている: TypeScript が戻っていないこと（撤去した 8 パスの
不在 **と** `.ts` の総数、**別々の claim**）、Svelte がどんな名前でも戻っていないこと、
`wrangler.jsonc` の `main` が shadow の出力先を指していること、`:warnings-as-errors` が
`:compiler-options` に在って `:build-options` に無いこと、ページが route 表から描かれる
こと、ADR が `cljs.reader` で読める tx-data であること。

## 2. テストを走らせる（ビルド不要・ブラウザ不要）

判断（`route.cljc`）と描画（`view.cljc`）は純 `.cljc` なので nbb だけで回る。

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:test:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > /tmp/run.cljs <<'EOF'
(require '[cljs.test :refer [run-tests]] 'natural-person.route-test)
(run-tests 'natural-person.route-test)
EOF
npx --yes nbb --classpath "$CP" /tmp/run.cljs
```

実際の出力:

```
Testing natural-person.route-test

Ran 5 tests containing 27 assertions.
0 failures, 0 errors.
```

何を固定しているか: `/xrpc/` は**空の nsid だけ** 400 にする（`/xrpc/a/b` は移行前の
rest parameter と同じく転送する。1 セグメントに絞るのは移行ではなく方針変更）、
**移行前に 404 だったものは 404 のまま**（`/healthz` `/readyz` `/_app/meta`）、
namespace 検査は無い（移行前の配備 handler も無かった）、MCP router の URL 解決
（空白だけの設定は未設定として扱う）、`result` / `structuredContent` の剥がし方、
そして**ページが route 表から描かれること**（固定値を焼いていたら落ちる）。

## 3. ページを描画して採点する

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > /tmp/render.cljs <<'EOF'
(require '["node:fs" :as fs] '[natural-person.view :as view] '[natural-person.route :as route])
(let [css (.readFileSync fs (str (.-DDS js/process.env) "/resources/jp_go_dds/dds.css") "utf8")]
  (.writeFileSync fs "/tmp/np-page.html"
    (view/render {:css css :routes route/routes
                  :vars [:APP_NANOID :APP_UI_TYPE :APP_PERFORMER_TYPE]
                  :mcp-url "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"}))
  (println "ok"))
EOF
DDS="$K/jp-go-digital-design-system" npx --yes nbb --classpath "$CP" /tmp/render.cljs

cd $K/design-quality && npx --yes nbb -m design-quality.cli score /tmp/np-page.html --min 95
```

実際の出力（末尾）:

```
aggregate: 100.00

axes scored: 10 (viewport, safe-area, dynamic-viewport, tap-targets, focus-visible,
                 reduced-motion, overflow-guard, color-scheme, responsive, semantics)
NOT scored: input-zoom, contrast — pass --extra-axes to include the optional ones
A pass says nothing about an axis that was not applied.

gate: aggregate 100.00 >= min 95.00 -> PASS
```

`--extra-axes` を付けると 12 軸すべてで採点され、**やはり 100.00 / PASS**。

⚠ **この数字が保証する範囲は狭い。** CLI 自身が「採点していない軸がある」と出力に書く。
上流の実測では**デザインシステムを完全に外しても 96.63 で PASS する**。
「デザインシステムが実際に入っている」と言えるのは §5 の smoke の 2 本目だけである。

## 4. bundle をビルドする

**高負荷ビルドは同時 1 本に制限されている**（superproject `CLAUDE.md` の resource
governor）。直接叩かず、必ず guard 経由で:

```bash
cd "$REPO"
node ~/github/com-junkawasaki/scripts/resource-guard.mjs run build -- \
  npx --yes shadow-cljs release worker
ls -la dist/worker.js
```

**lock を他セッションが持っていると exit 2 で拒否される。迂回しない** ——
`resource-guard: build is already running (pid=…)` はエラーではなく**順番待ち**である
（この walk では最大 5 回待った）。

実際の出力（末尾）:

```
[:worker] Build completed. (55 files, 12 compiled, 0 warnings, 9.27s)
```

```
dist/worker.js   245,741 bytes
sha256           1a916de26087b68543006c31e7085babb794a293d5e7b2e13bb32a956bd38925
```

### 壊れた var はビルドを **落とす**（2026-08-19 実測）

`shadow-cljs.edn` の `:compiler-options` に `:warnings-as-errors true` を入れた。入れる
前は、存在しない var を参照しても shadow は **WARNING** を出して **exit 0** し、最初の
リクエストで壊れる bundle を書いていた ——「ビルドが通った」は検査ではなかった
（**落ちようが無かった**）。

この repo で実際に落として確かめた。`src/natural_person/worker.cljs:109` の
`route/dispatch` を、存在しない `route/dispatch-nonexistent` に改名して再ビルドする:

```
------ ERROR -------------------------------------------------------------------
 File: /private/tmp/app-natural-person-cljs/src/natural_person/worker.cljs:109:44
```

| | exit | `dist/worker.js` sha256 |
|---|---|---|
| 改名前 | **0** | `1a916de2…6bd38925` |
| 改名後（option 有り） | **1** | `1a916de2…6bd38925`（**不変**） |
| 改名後（option を外す） | **0** | `e0a81317…6b53de6c`（**別物を出荷**） |
| 戻して再ビルド | **0** | `1a916de2…6bd38925` |

**落ちたビルドは bundle を出荷しない** —— sha256 が 1 バイトも動いていないことが
それを言っている。option を外して出荷された bundle は §5 の smoke で
`Cannot read properties of undefined (reading 'h')` を投げた。

キーは `:build-options` ではなく **`:compiler-options`** に置く。置き場所を間違えると
**黙って無視される** —— この option が防ぐはずの失敗（落ちようの無い検査）そのものになる。
**検証器はここを EDN として parse して確かめる。grep では確かめられない**（誤配置した
状態でも `grep -c 'warnings-as-errors'` は 3 を返す。説明文が検査を通してしまう）。

## 5. ビルドした成果物を実際に叩く

ここが deploy されるものに触る唯一の検査である。

```bash
cd "$REPO" && npx --yes nbb scripts/smoke-worker.cljs dist/worker.js
```

実際の出力:

```
PASS	default export has fetch	expected=true	actual=true
PASS	GET / status	expected=200	actual=200
PASS	GET / is html	expected=true	actual=true
PASS	page advertises /health	expected=true	actual=true
PASS	page advertises /xrpc/:nsid	expected=true	actual=true
PASS	page shows a var key	expected=true	actual=true
PASS	page hides other var values	expected=false	actual=false
PASS	page shows the relay target it uses	expected=true	actual=true
PASS	page uses the design system components	expected=true	actual=true
PASS	page carries the stylesheet itself	expected=true	actual=true
PASS	GET /health status	expected=200	actual=200
PASS	health names its routes	expected=true	actual=true
PASS	GET /healthz stays 404 (never deployed)	expected=404	actual=404
PASS	POST /xrpc/ status	expected=400	actual=400
PASS	OPTIONS preflight	expected=204	actual=204
PASS	unknown path	expected=404	actual=404
PASS	wrong method	expected=405	actual=405
OK	the built bundle answers as the route table says
```

**bundle が無ければ exit 2**（「判定できなかった」であって合格ではない）:

```
$ npx --yes nbb scripts/smoke-worker.cljs dist/does-not-exist.js
UNDETERMINED	no bundle at /…/dist/does-not-exist.js
Refusing to report a pass: build it first (see docs/operator-quickstart.md S4).
$ echo $?
2
```

0 / 1 / 2 は**別の答え**である（この walk で 3 つとも出した）。

## 6. Workers ランタイム（workerd）で動かす

Node で import する smoke より強い検査。実際の workerd で起こす。

```bash
cd "$REPO/appview/etzhayyim-wasm-natural-person-np02priv9"
npx --yes wrangler@latest dev --local --port 8802 --ip 127.0.0.1
```

`[wrangler:info] Ready on http://127.0.0.1:8802` が出たら別シェルで叩く。実際の出力:

```bash
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://127.0.0.1:8802/
# 200 text/html; charset=utf-8

curl -s http://127.0.0.1:8802/health
# {"ok":true,"app":"natural-person","runtime":"cljs","routes":["/","/health","/xrpc/:nsid"]}

curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8802/xrpc/
# 400

curl -s -X POST -H 'content-type: application/json' -d '{}' \
  http://127.0.0.1:8802/xrpc/com.etzhayyim.apps.naturalPerson.getPerson
# {"error":"MCP router unreachable","detail":"internal error; reference = …",
#  "url":"https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"}

curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'content-type: application/json' \
  -d '{}' http://127.0.0.1:8802/xrpc/a/b
# 502   ← 多段パスは移行前と同じく **転送する**（拒否しない）

curl -s -o /dev/null -w '%{http_code}\n' -X OPTIONS http://127.0.0.1:8802/xrpc/x   # 204
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8802/xrpc/x              # 405

for p in /healthz /readyz /_app/meta /nope; do
  printf '%-12s ' $p; curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8802$p
done
# /healthz     404
# /readyz      404
# /_app/meta   404
# /nope        404
```

`compatibility_flags`（`nodejs_compat` / `nodejs_als`）は SvelteKit の adapter-cloudflare
由来で、この bundle には要らない。**撤去は憶測ではなくこの実測で確かめてから行った** ——
flags 無しの設定のまま workerd を起こし、上の全 route が期待どおり答えることを見ている。

### XRPC が 502 を返すのは正常な現在地

上流 `mcp.etzhayyim.com` が **DNS で引けない**（README「呼び先」）ので中継の `fetch()` が
throw する。**手元の設定ミスではない。** 移行前はこれが素の 500
`{"message":"Internal Error"}` になって原因が応答に残らなかったが、いまは 502 と試した
URL を返す。

```bash
dig +short mcp.etzhayyim.com A          # 空 = 引けない
dig +short etzhayyim.com A              # 172.67.179.128 / 104.21.51.111 = apex は生きている
```

上流を差し替えたいときは `wrangler.jsonc` の `AGENTGATEWAY_MCP_ROUTER_URL`（または
`MCP_ROUTER_URL`）を向け直す —— コードは両方を見て、どちらも無ければ既定値に落ちる。

## 7. 後片付け

`.gitignore` が `dist/` `.shadow-cljs/` `node_modules/` `.cpcache/` `.wrangler/` を
無視するので、ここまで踏んでも `git status --porcelain` は空である（移行前は未追跡
ファイルが 4 つ残った）。

## 8. deploy

```bash
cd "$REPO/appview/etzhayyim-wasm-natural-person-np02priv9"
npx wrangler deploy
```

**ただし route が指すホストは解決しない**（`natural-person.etzhayyim.com` /
`np02priv9.etzhayyim.com` とも NXDOMAIN）。deploy が成功しても誰も到達できない。
中継先 `mcp.etzhayyim.com` も同様なので、到達できたとしても中継は **502 を返す**。
**この walk では deploy していない。**

superproject の規約として、本番 deploy は `origin/main` を包含した checkout からのみ
行う（PreToolUse hook `wrangler-deploy-main-sync-guard.cljs` が強制する）。

## 9. この手順で分かること／分からないこと

**分かること**: ビルドが通ること、bundle が Worker の形で答えること、実 workerd で
全 route が期待どおり答えること、route が表と一致すること、上流が引けないこと。

**分からないこと**: XRPC のドメイン挙動。上流 MCP router が不在なので、`getPerson` などが
**何を返すべきか**はこの手順では一切検証できない。CLAUDE.md が記述するコホート生成・
compliance 評価はこの repo の外に在り、ここから叩いて確かめる方法は今は無い。
