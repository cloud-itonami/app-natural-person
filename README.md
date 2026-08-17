# app-natural-person

**この repo は `natural-person.etzhayyim.com` の edge appview である。** 人口統計の
コホート生成・人物同定・法域別 compliance 評価そのものは**ここには無い** —— ここに在るのは、
XRPC 要求を MCP router へ中継する SvelteKit worker 1 本と、その配備設定である。

`CLAUDE.md` は Phase 1A〜Phase 2 の約 20 コマンド・26 次元・19 法域を記述しているが、
**それらを実装したコードはこの repo の 14 ファイル（21 KB）に 1 行も含まれていない。**
CLAUDE.md を読んでここに実装を探しに来た読み手が最初に必要とするのはこの事実なので、
名乗りの直後に置く。

| | |
|---|---|
| nanoid | `np02priv9` |
| 宣言 DID | `did:web:natural-person.etzhayyim.com`（**未解決 —— 下記欠陥 B**） |
| 実行形態 | SvelteKit + `@sveltejs/adapter-cloudflare` → Cloudflare Worker |
| 上流 | `https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message`（MCP router） |
| 配備単位 | `appview/etzhayyim-wasm-natural-person-np02priv9/` |

手順は `docs/operator-quickstart.md`。以下は 2026-08-17 (UTC) に**実際に測って**分かった
現在地であり、推測は含まない。測り方は各項に書いてある。

---

## 1. 読み手が最初に踏む地雷 —— 開くファイルと、動くファイルが違う

`src/` の下には `app.ts` が 1 本だけ在る。名前も、冒頭のコメント
（`natural-person.etzhayyim.com thin edge facade`）も、この repo の入口であるように読める。
**動いていない。**

配備されるのは `wrangler.jsonc` の `main` が指す先である:

```jsonc
"main": "svelte/.svelte-kit/cloudflare/_worker.js"   // ← SvelteKit のビルド成果物
```

つまり実際に要求を処理するのは `svelte/src/routes/xrpc/[...path]/+server.ts` であって、
`src/app.ts` ではない。**ビルド成果物を検索して確認した**:

```
grep -rl "dispatcher.etzhayyim.com" .svelte-kit/   → 0 件   （src/app.ts の上流）
compiled endpoint 内の URL                          → https://mcp.etzhayyim.com/... のみ
```

`src/app.ts` はどのビルド出力にも入らない。**dead code である。**

### 2 つのファイルは同じことをしていない

これが問題なのは、単に片方が使われていないからではない。**両者の振る舞いが違う**ので、
`src/app.ts` を読んで得た理解は本番に対して誤りになる。`wrangler dev` を起動して
実測した差:

| 挙動 | `src/app.ts`（読まれる／動かない） | 配備される handler（実測） |
|---|---|---|
| `GET /health` `/healthz` `/readyz` | JSON 200 を返す実装が在る | **404** |
| `GET /_app/meta` | 同上 | **404** |
| namespace 外の NSID | `com.etzhayyim.apps.naturalPerson.` 以外は **404** | **中継する**（下記） |
| `GET /xrpc/<nsid>` | 受理（query を body へマージ） | **405** `GET method not allowed` |
| 上流 | `dispatcher.etzhayyim.com` | `mcp.etzhayyim.com` |

namespace 検査が無いことの実測 —— 無関係な NSID が、自分の NSID と**同じ応答**になる:

```
POST /xrpc/com.etzhayyim.apps.naturalPerson.getPerson  → 500 {"message":"Internal Error"}
POST /xrpc/com.example.totallyUnrelated.doAnything     → 500 {"message":"Internal Error"}   ← 同一
```

`src/app.ts` なら後者は 404 だった。配備側は任意の tool 名を MCP router へ素通しする。
**この差は設計判断として記録されていない** —— 移行の副産物なのか意図なのかを、この repo からは
判定できない。

デプロイされる route は 2 本しか無い（ビルド manifest で確認）: `/` と `/xrpc/[...path]`。

## 2. 測って見つけた欠陥（未修正 —— この反復では docs だけを触った）

**A. `/health` が無い。** 上表のとおり 404。`src/app.ts` は `/health` `/healthz` `/readyz`
`/_app/meta` の 4 つを実装しているが、配備されないので存在しない。監視をこの URL に
向けている経路があれば、それは常に落ちていると報告する。

**B. この repo が名乗る DID が解決できない。** `did:web` は
`https://<host>/.well-known/did.json` で解決するが、host が引けない:

```
natural-person.etzhayyim.com   DNS: no A record
np02priv9.etzhayyim.com        DNS: no A record
mcp.etzhayyim.com              DNS: no A record      ← 上流。配備しても中継先が無い
dispatcher.etzhayyim.com       DNS: no A record
site.etzhayyim.com             DNS: no A record      ← CLAUDE.md の web enrichment 経路
etzhayyim.com                  A=172.67.179.128  /.well-known/did.json → 200
```

**apex は生きていて did.json も 200 を返す**ので、did:web の仕組み自体は機能している。
対照に `kotobase.net` → 200 も確認したので、測定側の問題ではない。**この 5 サブドメインが
実在しない**。したがって `wrangler.jsonc` の `routes`（`natural-person.etzhayyim.com/*` と
`np02priv9.etzhayyim.com/*`）も、今そのまま deploy して到達可能にはならない。

**C. 上流が落ちているときの応答が 500 になる。** `+server.ts` は `!upstream.ok` を
502 + upstream payload で扱う分岐を持つが、**`fetch()` 自体が throw する経路**（host が
引けない・接続不能）を捕まえていない。実測ではそれが素の `{"message":"Internal Error"}` 500 に
なり、原因が応答に残らない。B の状態では常にこの経路を通る。

**D. NOTICE と kotodama.jsonld が、存在しないファイルを指している。**

| 参照元 | 参照先 | 実在 |
|---|---|---|
| `NOTICE` | `CHARTER-RIDER.md`（ライセンス条件の本文） | **無い** |
| `kotodama.jsonld` の `component.path` | `component.wasm` | **無い** |

D の後者は `runtimeType: "worker"` と `wrangler.jsonc` の `CompiledWasm` glob と併せて
「WASM component が在る」と読ませるが、repo に `.wasm` は 1 つも無い。

**E. `+page.svelte` が、隣の設定ファイルと矛盾する。** 生成物に
`routeCount: 0` / `routes: []` / `vars: []` が焼き込まれており、画面には
*"No public vars are declared in the nearest wrangler config"* と表示される。
実際には `wrangler.jsonc` が **8 個の vars と 2 個の routes** を宣言している。
生成時点の状態が固まったまま更新されていない。

**F. `src/app.ts` の `/health` が返す `businessLogic` パスが存在しない。**
`40-engine/kotoba/crates/kotoba-kotodama/py/src/kotodama/ingest` を
`etzhayyim/root`（checkout 済み）で確認したが無い。Rust crate 群は撤去済み
（ADR-2607072000）なので、この記述は撤去前の地図である。同じ応答が指す `bpmn` パスの方は
**実在する**（下記 3）。

**G. lockfile も `.gitignore` も無い。** quickstart を最後まで踏むと未追跡ファイルが 4 つ残り
（`node_modules/` `.svelte-kit/` `package-lock.json` `.wrangler/`）、92 パッケージは固定されない。
`package.json` の直接依存 7 本はすべて `^` レンジである。

## 3. CLAUDE.md が記述する系と、実在する成果物の対応

CLAUDE.md の約 20 コマンドに対し、上流 `etzhayyim/root` の
`00-contracts/bpmn/com/etzhayyim/natural-person/` に在る BPMN は **4 本だけ**である。

| BPMN | CLAUDE.md に記載 |
|---|---|
| `generateCohortBatch.bpmn` | ✅ Phase 1B |
| `materializeAllLatentEntities.bpmn` | ❌ 記載なし |
| `reconcileVisibility.bpmn` | ❌ 記載なし |
| `seedLatentEntities.bpmn` | ❌ 記載なし |

**記載 20 のうち BPMN が在るのは 1 つ。BPMN 4 本のうち 3 本は記載が無い。**
どちらの側が現在地なのかは、この repo からは判定できない。

## 4. この repo は上流と重複している

`migration.edn` は出所を `etzhayyim/root` rev `c9e7df4b` の
`60-apps/etzhayyim-project-natural-person` と記録し、行き先を
`cloud-itonami/app-natural-person` と宣言している。**しかし上流の元パスは削除されていない。**

実測（`diff -rq`、生成物と移行時の追加 2 ファイルを除く）: **両者は現在バイト単位で同一**。

つまり同じ 12 ファイルが 2 箇所に在り、**どちらに書けばよいかを宣言しているものが無い。**
今は一致しているので害が見えないが、片方だけが直された時点で静かに分岐する。

## 5. 境界（最近接 repo との違い）

- **`etzhayyim/root`** — 上流の monorepo。BPMN 契約（`00-contracts/`）と、この appview の
  元パスを今も持つ。**ドメイン logic の在処はこちら側**であって、この repo ではない。
- **`cloud-itonami/app-legal-entity` / `app-legal-corpus`** — 同じ cloud-itonami の
  app 系列。法人・法令コーパスを扱い、自然人は扱わない。
- この repo が担うのは **edge の 1 層だけ** —— 到着した XRPC 要求を MCP router へ渡すこと。

## 6. 直していないこと

この反復は成熟度 loop の **docs 軸 1 反復**なので、**`README.md` と
`docs/operator-quickstart.md` の 2 ファイルしか追加していない。** source・設定・依存は
1 バイトも触っていない。上の A〜G は測って記録しただけで、**1 件も修正していない**:

- **A / 上表の挙動差** は、`src/app.ts` を消すのか、配備側に寄せるのかが設計判断
  （namespace 検査と `/health` を復活させるべきかは、この repo の外の運用が決める）。
- **B** はホストを作る/DNS を張る話で、この repo の中では直せない。
- **C** は `+server.ts` に `try`/`catch` を足すだけで直るが、source の変更なので分けた。
- **D** の `CHARTER-RIDER.md` は本文の所在を知る必要があり、`component.wasm` は
  そもそも配布物として要るのかの判断が要る。
- **E** は生成器が上流に在るので、ここで手書きすると次の生成で消える。
- **G** は sibling の `app-legal-corpus` / `app-legal-entity` も同じ状態なので、
  比較可能性のため揃えて残した。
