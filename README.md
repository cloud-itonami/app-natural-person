# app-natural-person

**この repo は `natural-person.etzhayyim.com` の edge appview である。** 人口統計の
コホート生成・人物同定・法域別 compliance 評価そのものは**ここには無い** —— ここに在るのは、
XRPC 要求を MCP router へ中継する Worker 1 本と、その配備設定である。

`CLAUDE.md` は Phase 1A〜Phase 2 の約 20 コマンド・26 次元・19 法域を記述しているが、
**それらを実装したコードはこの repo に 1 行も含まれていない。** CLAUDE.md を読んで
ここに実装を探しに来た読み手が最初に必要とするのはこの事実なので、名乗りの直後に置く。

**2026-08-19 に TypeScript/Svelte から ClojureScript へ移行した**（`docs/adr/0001`）。
数字はすべて `scripts/verify-docs-claims.cljs` が tree から再計算して検査する。

| | |
|---|---|
| nanoid | `np02priv9` |
| 宣言 DID | `did:web:natural-person.etzhayyim.com`（**未解決 —— 下記「呼び先」**） |
| 実行形態 | ClojureScript → shadow-cljs `:esm` → Cloudflare Worker |
| 上流 | `https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message`（MCP router、**未解決**） |
| 配備単位 | `appview/etzhayyim-wasm-natural-person-np02priv9/` |

手順は `docs/operator-quickstart.md`。以下は 2026-08-19 (UTC) に**実際に測って**分かった
現在地であり、推測は含まない。

---

## deploy されるものは、いま読んでいるソースである

```
src/natural_person/route.cljc    判断（どの handler が答えるか）  ← 純 .cljc、テスト対象
src/natural_person/view.cljc     ページ（jp-go-dds の hiccup）    ← 純 .cljc、テスト対象
src/natural_person/worker.cljs   Request/Response に触る唯一の層
        ↓ shadow-cljs :target :esm
dist/worker.js                   ← wrangler.jsonc の "main" が指すもの
```

移行前は `main` が `svelte/.svelte-kit/cloudflare/_worker.js`（**tree に存在しない**
ビルド成果物）を指し、読み手が開く `src/app.ts` は**どの bundle にも入っていなかった**
——しかも両者の振る舞いは違った（`src/app.ts` は `/health` を持ち namespace 検査をするが、
配備側はどちらも持たない）。**`src/app.ts` を読んで得た理解は本番に対して誤りだった。**

いまは `main` が指す bundle が上のソースからコンパイルされたものなので、その形は構造的に
起こり得ない。`scripts/verify-docs-claims.cljs` が **shadow の出力先と wrangler の `main` と
export の ns 名の 3 つが噛み合っていること**を検査し、噛み合わなくなれば落ちる。

判断を `.cljc` に置いてあるのは、ブラウザもビルドも無しにテストするためであり、ingress
capability が qualify した時に**最初に `.kotoba` へ移る部分**だからである（入口を当面
cljs に置くのは ADR-2606290000 の判断）。

## 公開ルート

| METHOD | PATH | 何をするか |
|---|---|---|
| GET | `/` | この appview の説明ページ |
| GET | `/health` | 生存確認。deploy された面が答えることを外から確かめられる |
| POST | `/xrpc/:nsid` | XRPC を MCP router へ中継する |
| OPTIONS | `/xrpc/*` | CORS preflight |

**この表の出所は `natural-person.route/routes` で、ページもそこから描く。** 移行前の
ページは `routeCount: 0` / `routes: []` / `vars: []` を literal で持ち、画面に
*"No public vars are declared in the nearest wrangler config"* と表示していた —— 隣の
`wrangler.jsonc` が **route 2・var 8** を宣言していることに気づけなかった。いまは route 表を
渡す側が持ち、ページは描くだけなので、両者がずれる余地が無い。

### 移行で変えていないこと・変えたこと

**変えていない（deploy されていた挙動をそのまま持ち越した）**:

- **多段パス `/xrpc/a/b` は転送する。** 移行前の SvelteKit route は rest parameter
  `[...path]` で受け、**空文字だけ**を 400 にしていた。1 セグメントに絞ると失敗の起きる
  場所と応答が変わる —— **それは移行ではなく方針変更**なので入れていない。
- **namespace 検査は無い。** 配備側は `com.etzhayyim.apps.naturalPerson.` 以外の NSID も
  素通ししており、それが deploy されている挙動である（`src/app.ts` の 404 は deploy
  されていない）。

**変えた（意図的、`docs/adr/0001` に理由付きで記録）**:

- **上流に到達できないときの応答を 500 → 502 にした。** 移行前は `fetch()` の throw を
  捕まえておらず素の `{"message":"Internal Error"}` 500 になり、原因が応答に残らなかった。
  いまは 502 と、試した URL を返す。`mcp.etzhayyim.com` は NXDOMAIN なので、これは
  想像上の経路ではなく**今日の既定の結末**である。
- **`GET /health` を 1 本足した。** 移行前は 404 だった（実測）ので、これは移行ではなく
  **追加**である。deploy された面が答えることを外から確かめる経路が無いのは、この ADR が
  記録した欠陥そのものなので足した。`src/app.ts` の `/health` が返していた
  `businessLogic` パスは**持ち越していない** —— `etzhayyim/root` の `origin/main` に対して
  git で実測して 0 ファイルで、撤去前の地図だからである。

**持ち越さなかった（黙って消していない）**:

- **`src/app.ts` の dispatcher 中継。** 宛先 `dispatcher.etzhayyim.com` は NXDOMAIN であり、
  かつ binding（`DISPATCHER_URL` / `DISPATCHER_INTERNAL_SECRET`）は `wrangler.jsonc` に
  **宣言されていない**。**deploy されたことが無く、bindings も無い**というのが持ち越さない
  条件である。
- **`/healthz` `/readyz` `/_app/meta`。** `src/app.ts` にあるが deploy されておらず実測 404。
  生やすと移行ではなくなるので 404 のままにした（unit test と smoke の両方で固定）。

## いま在るもの — 18 ファイル

| 面 | ファイル |
|---|---|
| 判断・描画・edge | `src/natural_person/{route.cljc, view.cljc, worker.cljs}` |
| テスト | `test/natural_person/route_test.cljc`（5 tests / 27 assertions） |
| ビルド | `deps.edn` / `shadow-cljs.edn` / `.gitignore` |
| 検査 | `scripts/{smoke-worker.cljs, verify-docs-claims.cljs}` |
| Worker 設定 | `appview/…/wrangler.jsonc` |
| actor 記述子 | `appview/…/kotodama.jsonld` |
| 設計 | `CLAUDE.md` |
| 由来・権利・識別 | `NOTICE` / `README.edn` / `migration.edn` |
| 文書 | `README.md` / `docs/operator-quickstart.md` / `docs/adr/0001-*.edn` |

**production の TypeScript は 0 本、正本言語（`.cljs`/`.cljc`）が 4 本。移行前は 3 対 0
だった。** Svelte 由来のファイルは 7 本 → 0 本。この数は検証器の claim なので、TS が
戻れば落ちる ——撤去した 8 パスに戻る場合（`removed-by-migration-absent`）も、別名で入る
場合（`production-ts-files`）も、**別々の claim** が捕まえる（両方を実際に落として確認済み）。

## ページが出す値・出さない値

env の**キー名**は出すが、値は出さない —— **中継先を除いて**。
`AGENTGATEWAY_MCP_ROUTER_URL` の値だけは、どこへ中継するかを運用者が見る必要があるので
意図的に表示する。

smoke はこれを**2 つの独立した印**で見る: 別の var に置いた sentinel が出ていないこと、
そして中継先の値が出ていること。片方だけだと「全部隠す」実装も「全部出す」実装も通る。

⚠ **この 2 つを同時に壊すと片方が緑に戻る**（この repo で実測）。値を漏らす mutation を
当てると、漏れた値の中に中継先 URL が含まれるので「中継先を表示している」が**表示を
やめていても緑になる**。個別に当てればそれぞれ赤くなる。**だから mutation は 1 つずつ
当てる。**

## デザインシステムの検査は 2 本ある

基盤は `kotoba-lang/jp-go-digital-design-system`（デジタル庁デザインシステム）。色・寸法は
`--hig-*` トークン契約だけで書き、raw hex も px フォントサイズも置かない。app 固有 CSS は
3 行。CSS は外部リクエストゼロの方針どおり `shadow.resource/inline` で bundle に焼く。

`dads-table` が在ることを 1 本で見る形は**落ちない検査**だった —— それは view が出力する
markup であって、CSS が 1 バイトも入っていないページにも現れる。実測（このページ、
2026-08-19、`(rc/inline …)` を `""` に替えて再ビルドして比較）:

| 探す文字列 | CSS 込み | CSS 無し |
|---|---|---|
| `dads-table` | 74 | **6**（0 にならない） |
| `--color-primitive-blue` | 45 | **0** |

だから 2 本に割った。**component を使ったか**（`class="dads-table"`）と、**stylesheet が
実際に入ったか**（`--color-primitive-blue`）は別の主張である。CSS を外してビルドし直すと
**後者だけが赤くなり前者は緑のまま**であることを確認済み。

決定論的 audit（`kotoba-lang/design-quality`）で **100.00 / 100（gate 95）**。既定 10 軸で
PASS、`--extra-axes` の 12 軸でも 100.00。**ただしこのスコアが保証する範囲は狭い** ——
CLI 自身が「10 軸を採点した / input-zoom と contrast は採点していない」と出力に書く。
上流の実測では**デザインシステムを完全に外しても 96.63 で PASS する**。「デザインシステムが
入っている」と言えるのは上の smoke の 2 本目だけである。

## 緑のビルドは検査ではなかった

`shadow-cljs.edn` の `:compiler-options` に `:warnings-as-errors true` を入れてある。
**`:build-options` ではない** —— shadow が読むのは `[:compiler-options :warnings-as-errors]`
で、置き場所を間違えると**黙って無視される**。この repo で両方向を実測した:

| | exit | `dist/worker.js` sha256 |
|---|---|---|
| 健全 | 0 | `1a916de2…6bd38925` |
| var を改名（option 有り） | **1** | `1a916de2…6bd38925`（**不変** = 出荷していない） |
| var を改名（option 無し） | **0** | `e0a81317…6b53de6c`（**別物を出荷した**） |
| 戻して再ビルド | 0 | `1a916de2…6bd38925` |

option 無しで出荷された bundle は、最初のリクエストで
`Cannot read properties of undefined (reading 'h')` を投げた。

**置き場所は EDN を parse して検査する。grep では検査できない** —— 実測: 誤配置した状態でも
`grep -c 'warnings-as-errors'` は 3、`grep -c ':compiler-options'` は 3 を返す
（この README と shadow-cljs.edn 自身のコメントが両方の文字列を含むため）。
**自分の説明文が自分の検査を通してしまう。**

## 呼び先が 1 つも解決しない（移行では直らない）

| ホスト | 役割 | DNS（2026-08-19 実測） |
|---|---|---|
| `natural-person.etzhayyim.com` | 公開ホスト（wrangler の route） | **NXDOMAIN** |
| `np02priv9.etzhayyim.com` | 同（nanoid 側） | **NXDOMAIN** |
| `mcp.etzhayyim.com` | `/xrpc/:nsid` の中継先 | **NXDOMAIN** |
| `dispatcher.etzhayyim.com` | 旧 `src/app.ts` の中継先（持ち越さず） | **NXDOMAIN** |
| `site.etzhayyim.com` | CLAUDE.md の web enrichment 経路 | **NXDOMAIN** |
| `etzhayyim.com` | apex | `172.67.179.128` / `104.21.51.111`（`/.well-known/did.json` 200） |

**apex は生きている**ので did:web の仕組み自体は機能している。対照に `kotobase.net` も
解決するので測定側の問題ではない。**この 5 サブドメインが実在しない。** deploy 先も
中継先も、いま存在しない。`/xrpc/` は到達できなければ **502 を返す** ——成功と同じ形で
隠さない。

## 由来（custody）

`migration.edn` は出所を `etzhayyim/root` rev `c9e7df4b` の
`60-apps/etzhayyim-project-natural-person` と記録する。移行後の状態:

- 継承した 4 ファイル（`NOTICE` / `README.edn` / `migration.edn` / `kotodama.jsonld`、
  計 3,358 バイト）は**いまも 1 バイトも変わっていない**（sha256 を検証器に固定）
- `wrangler.jsonc` は**意図的に変更**した（`main` の付け替え、消えた SvelteKit client を
  指す `assets` の撤去、`compatibility_flags` の撤去、`APP_FRAMEWORK` の更新）
- `CLAUDE.md` も**意図的に変更**した（この repo の runtime が cljs であることを冒頭に
  足した）。この 2 つは byte 一致集合から外し、**内容で検査する** —— 意図的な変更と
  勝手な変更を区別するため
- TypeScript/Svelte の 8 ファイルは**移行で撤去**した

### `migration.edn` の `:allowed-additions` は既に現実と合っていない

`{:identity {:allowed-additions ["README.edn" "migration.edn"]}}` は「上流の tree に
この 2 つを足したもの」と主張するが、実際には `README.md` / `docs/` / `src/` / `test/` /
`scripts/` が在る。**この移行より前から合っていない**（2026-08-17 の docs 反復で
README.md と operator-quickstart.md が入った時点で）。継承した custody ファイルなので
**書き換えていない** —— 直すなら custody 側の決定として行う。

### 上流との重複は解消済み（この repo の前版の記述は古い）

このファイルの 2026-08-17 版は「上流の元パスは削除されていない / 両者はバイト単位で同一」と
書いていた。**2026-08-19 時点でそれは偽である。** git に訊いて実測（`ls` ではなく
`git ls-tree`。cone 外・未 checkout・削除済みは `ls` では同じ顔をする）:

| ref | `60-apps/etzhayyim-project-natural-person` の追跡ファイル数 |
|---|---|
| `origin/main`（`bec359a7`） | **0** |
| `migration.edn` の rev（`c9e7df4b`） | 12 |

上流が `refactor(apps): extract twelve-file band (#3248)`（`c3a74d20`）で元パスを削除した。
**どちらに書けばよいかが宣言されていない状態は解消している** —— 正本はこの repo である。

## 残っている欠陥（移行では直っていない）

1. **`NOTICE` が存在しないファイルを指す。** `CHARTER-RIDER.md`（ライセンス条件の本文）は
   この repo に無い。継承した custody ファイルなので触っていない。
2. **`kotodama.jsonld` の `component.path` が `component.wasm` を指すが、`.wasm` は
   1 つも無い。** `wrangler.jsonc` の `rules`（`CompiledWasm` glob）も併せて「WASM
   component が在る」と読ませるが、無い。**移行前から inert** で、移行はそれを真にも
   偽にもしないので `rules` は**残した**（撤去は別の決定）。
3. **CLAUDE.md の約 20 コマンドに対し、上流の BPMN は 4 本だけ**（`origin/main` で実測）。
   うち CLAUDE.md に載るのは `generateCohortBatch` の 1 本で、残る 3 本
   （`materializeAllLatentEntities` / `reconcileVisibility` / `seedLatentEntities`）は
   記載が無い。どちらの側が現在地なのかは、この repo からは判定できない。
4. **ホストが解決しない**（上記）。移行はそれを直さない。deploy するか retire するかは
   別の決定。

## 検証

```bash
npx --yes nbb scripts/verify-docs-claims.cljs .          # <dir> は先頭に置く
```

exit 0 = 全一致 / 1 = 食い違い / **2 = 判定できなかった**（0 と区別する）。
テスト・ビルド・smoke は `docs/operator-quickstart.md`。
