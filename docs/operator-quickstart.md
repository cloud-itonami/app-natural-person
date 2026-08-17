# operator quickstart — app-natural-person

`natural-person.etzhayyim.com` の edge appview を、手元で **build して起動して叩く**まで。
下の手順は 2026-08-17 (UTC) に clean-room（`node_modules` と `.svelte-kit` を消してから）
実走した結果であり、掲載している数字は**そのとき実際に出た値**である。

読む前に `README.md` の §1 を見ること —— **`src/app.ts` は動いていない。**
ここで build・起動するのは SvelteKit 側である。

実測環境: macOS (darwin 25.3.0) / node **v26.3.0** / npm **11.16.0**。

## 0. 場所

配備単位は repo 直下ではなく 1 段下:

```bash
cd appview/etzhayyim-wasm-natural-person-np02priv9
```

`wrangler.jsonc` はここに在り、npm プロジェクトは**さらにその下の `svelte/`** に在る。
`npm install` を `wrangler.jsonc` の隣で打っても `package.json` が無いので何も起きない。

## 1. 依存を入れる

```bash
cd svelte
npm install
```

実測: `added 92 packages, and audited 93 packages`（exit 0）。所要は 2 回の実走で 6s と 13s
だった —— **件数は安定するが時間はしない**ので、時間を合格判定に使わない。

**警告が 1 つ出るが、無視してよい**（実測で build まで通ることを確認済み）:

```
npm warn allow-scripts 3 packages have install scripts not yet covered by allowScripts:
npm warn allow-scripts   esbuild@0.25.12, esbuild@0.28.1, workerd@1.20260811.1
```

これはこのマシンの `~/.npmrc` に `allow-scripts[]` が設定されているために出るもので、
repo 側の問題ではない。**postinstall が抑止されたままでも §2 の build と §3 の起動は
通る**（そこまで確認した）。repo に `.npmrc` を足す必要は無い。

⚠ **lockfile が無い**（README §2-G）。この `npm install` は毎回レンジを解決し直すので、
上の 92 という数字は将来ずれる。

## 2. 型検査と build

```bash
npm run check
```

実測: `COMPLETED 142 FILES 0 ERRORS 0 WARNINGS 0 FILES_WITH_PROBLEMS`（exit 0）。
このスクリプトは `svelte-kit sync` を先に走らせるので、`.svelte-kit/tsconfig.json` が
無い状態（clone 直後）でも単体で通る。

```bash
node /path/to/com-junkawasaki/scripts/resource-guard.mjs run build -- npm run build
```

**superproject の resource governor を通すこと**（repo-wide mandatory。高負荷 build は
同時 1 本に制限されている）。実測: client と server の 2 段が `✓ built in ...` を出し、
最後に `Using @sveltejs/adapter-cloudflare ✔ done`。所要は同一ツリーの 2 回で
client 535ms→218ms / server 4.71s→1.93s と振れた（キャッシュ差）ので、**時間ではなく
下の成果物の実在で判定する**。

build が作るのが、`wrangler.jsonc` の `main` が指す実体である:

```
svelte/.svelte-kit/cloudflare/_worker.js       ← 4,335 bytes（これが配備される）
svelte/.svelte-kit/cloudflare/client/          ← ASSETS binding が指す静的資産
```

**build する前に §3 を実行しても動かない** —— `main` の指す先がまだ無い。

## 3. 手元で起動して叩く

`wrangler.jsonc` の在る階層（`svelte/` の 1 つ上）に戻ってから:

```bash
cd ..
npx --yes wrangler@latest dev --port 8799 --local
```

`[wrangler:info] Ready on http://localhost:8799` が出たら別シェルで叩く。

### 実在する route は 2 本だけ

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8799/          # → 200
curl -s -X POST http://localhost:8799/xrpc/com.etzhayyim.apps.naturalPerson.getPerson \
     -H 'content-type: application/json' -d '{}'                        # → 500（後述）
```

### 期待してはいけない route（実測 404）

```bash
for p in /health /healthz /readyz /_app/meta; do
  printf '%-12s ' "$p"; curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:8799$p"
done
# /health      404
# /healthz     404
# /readyz      404
# /_app/meta   404
```

これらは `src/app.ts` が実装しているが、そのファイルは配備されない（README §1）。
**監視をこの URL に向けない。**

### XRPC が 500 を返すのは正常な現在地

上流 `mcp.etzhayyim.com` が **DNS で引けない**（README §2-B）ので、中継の `fetch()` が
throw し、素の 500 になる:

```
POST /xrpc/com.etzhayyim.apps.naturalPerson.getPerson  → 500 {"message":"Internal Error"}
```

**手元の設定ミスではない。** 上流を差し替えたいときは `wrangler.jsonc` の
`AGENTGATEWAY_MCP_ROUTER_URL`（または `MCP_ROUTER_URL`）を向け直す —— コードは
両方を見て、どちらも無ければ既定値にフォールバックする。

自分で確かめるなら:

```bash
dig +short mcp.etzhayyim.com A          # 空 = 引けない
dig +short etzhayyim.com A              # 172.67.179.128 = apex は生きている
```

### namespace 検査は無い

無関係な NSID も同じ経路を通る（README §1 の表）:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  http://localhost:8799/xrpc/com.example.totallyUnrelated.doAnything \
  -H 'content-type: application/json' -d '{}'      # → 500（自分の NSID と同じ = 弾いていない）
```

`GET` は通らない（`405 GET method not allowed`）。POST のみ。

## 4. 後片付け

`.gitignore` が無いので、ここまでで未追跡ファイルが **4 つ**残る（repo 直下で確認）:

```bash
git status --porcelain
# ?? appview/.../.wrangler/                  ← §3 の wrangler dev が作る
# ?? appview/.../svelte/.svelte-kit/         ← §2 の build が作る
# ?? appview/.../svelte/node_modules/        ← §1 が作る
# ?? appview/.../svelte/package-lock.json    ← §1 が作る
```

`.wrangler/` は §3 を実行したときだけ増える（§2 で止めれば 3 つ）。

**commit しないこと。** 消すなら:

```bash
A=appview/etzhayyim-wasm-natural-person-np02priv9
rm -rf "$A"/.wrangler "$A"/svelte/{node_modules,.svelte-kit,package-lock.json}
```

## 5. deploy について

このリポジトリの手順としては**書けない**。`wrangler.jsonc` の `routes` が指す
`natural-person.etzhayyim.com` と `np02priv9.etzhayyim.com` は、いま **DNS に存在しない**
（README §2-B）。ゾーンとホストが用意されていない状態で deploy 手順を書いても踏めないので、
確かめずに書かない。

なお superproject の規約として、本番 deploy は `origin/main` を包含した checkout からのみ
行う（PreToolUse hook `wrangler-deploy-main-sync-guard.cljs` が強制する）。

## 6. この手順で分かること／分からないこと

**分かること**: build が通ること、worker が起動すること、route が 2 本であること、
上流が引けないこと。

**分からないこと**: XRPC のドメイン挙動。上流 MCP router が不在なので、
`getPerson` などが**何を返すべきか**はこの手順では一切検証できない。
CLAUDE.md が記述するコホート生成・compliance 評価は
この repo の外に在り（README §3）、ここから叩いて確かめる方法は今は無い。
