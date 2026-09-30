# BLUFFアプリケーションサーバー仕様書

BLUFFアプリケーションサーバーの仕様はこのファイルをSSOTとする。ルール・データ構造・API・状態管理について、実装で迷ったらここを見る。

---

## 1. ゲームの説明

全員のサイコロの合計について「○個の△がある」と言う（Bid）。異議はChallenge（APIも`CHALLENGE`）。最後にサイコロを持っていた人が勝ち。

### 1.1 今回の前提（対CPU・PvPはやらない）

- 別の人とネット対戦する（PvP）想定はやめる（時間の都合）。相手はCPUとする。
- 人間プレイヤーは原則1人（クライアントから操作するのはこの人だけ）。あとはサーバがCPUとして手を進める。
- ゲームのルールそのもの（Bid / Challenge / STAR / ターン順など）は同じ。違うのは人間がクライアントから動かすのが一人だけで、CPUの手はサーバが進めることだけ。
- CPUの人数は、ゲーム作成時にクライアントが1〜5の範囲で選ぶ（`POST /games`の`cpuCount`）。人間1人 +CPUが合計2〜6人の卓になる。

### 1.2 BLUFFにおける用語

宣言への異議はChallenge（日本語ではダウトと書かれることが多い気がする）。

| 意味               | ルール本文での呼び方  | このプロジェクトのコード                         |
| ------------------ | --------------------- | ------------------------------------------------ |
| 宣言に異議を唱える | Challenge／チャレンジ | HTTP: `"type": "CHALLENGE"`、Java: `challenge()` |
| 宣言               | Bid                   | `bid` / `BID`                                    |

メモ: なぜBidか

- このゲームの宣言はオークションの競り上げに似ており、前の人より必ず強いor高いことを言わないといけない。ボード盤面で時計回りに一方向にしか進めないのがまさにそれで、英語のbidには「入札する/値をつける」というニュアンスがあり、この競り上げていく構造にぴったりなため。

---

## 2. 基本ルール

### 2.1 人数とサイコロ

- 卓にいるのは2〜6人。内訳は人間1人+CPUが1〜5人（CPUの人数は作成時に`cpuCount`で選ぶ。8章）。
- 最初は1人5個のサイコロを持つ。

### 2.2 出目が見える範囲

- 人間側は、自分の出目だけ見える（`viewerPlayerId`が自分のときだけ`myDice`を返す、など）
- 人間からは、CPUも含め他プレイヤーの出目は見えない（APIでは他の`dice`の中身は返さない。サーバ内部ではCPUの出目は計算に使う）
- Challengeが呼ばれてから罰の適用が終わるまでのあいだは、原作どおり「卓に全員の出目を出した」状態として扱う。クライアントは結果確認のため、一時的に全員の出目を返してよい（8.5の返却形は実装でそろえる）

### 2.3 出目とSTAR

各ダイスの面:

| 表記 | 意味                                     |
| ---- | ---------------------------------------- |
| 1–5  | その数字                                 |
| STAR | なんの数字としても数えてよい（ワイルド） |

数え方（Bidの解釈とChallengeで使う「実際の個数」で共通）  
`A`を求めるとき数えるのは、**その時点で生存している全プレイヤーが持っているダイスの面**の集合である。原作どおり、Challengeが宣言されたら全員のダイスを卓に出して表向きに公開し、その公開された集合について次のルールで数える（2.10）。実装上は卓用の別配列を持たず、各`Player`の`dice`を合わせたものをそのまま使ってよい。

- `face`が1〜5のとき: その数字の面の個数とSTARの個数を足す（STARはその宣言における数字として数える）。
- `face`がSTAR（APIでは`6`）だけを宣言するとき（「☆の個数のみ」）: STARの面の個数だけを数える（そのラウンドでは数字面はこの宣言の対象に含めない）。

この数を`A`（実際の個数） と呼ぶ。

- `A` >= `quantity` … そのBidは真（「少なくとも」と宣言したので、ちょうど同じも含む）
- `A` < `quantity` … そのBidは言い過ぎ（偽）

例（`face`が数字）: 「3個の5」で、全体が`5, 5, STAR` → `A` = 2 + 1 = 3 → 宣言は真。

### 2.4 Bid（宣言）

「全員のダイスを足し合わせたとき、宣言した`face`について（STARを2.3のとおり数えて）少なくとも`quantity`個ある」という宣言。

### 2.5 次にできる宣言（前のBidのあと）

次の番は、前のBidに続けて許される次のBidを言うか、Challengeするかのどちらかだけ。  
原作（READMEのref）では、前の宣言より**多い個数**を言うか、**より大きな出目**に変更する。出目を大きくした場合は個数を増やさなくてよい。個数を増やす場合は出目を小さくしてもよい。

記号: 直前の宣言を（`Qp`, `Fp`）、新しい宣言を（`Qn`, `Fn`）。`Fp` / `Fn`は4章の整数（`6` = STAR）。

数字同士（`Fp`と`Fn`がどちらも1〜5）

次が有効になる例（前が「3が9個」）:

- 出目を上げる。個数は前と同じでもよい。例: 「4が9個」。
- 個数を上げる。出目は同じでも、大きくしても小さくしてもよい。例: 「3が10個」「4が10個」「2が10個」。

次は無効:

- 個数が同じで出目が同じか小さい。例: 「3が9個」「2が9個」。
- 個数を減らす。例: 「5が8個」。

数字同士のときの判定式（`Fp`と`Fn`がともに1〜5）

次が有効になるのは次のどちらかを満たすとき（個数→出目の辞書順で「強くなる」ことと同じ）。

- `Qn` > `Qp`
- `Qn` = `Qp`かつ`Fn` > `Fp`

STARを含む宣言の追加条件

- 前が数字（1〜5）で、今回がSTAR（`Fn = 6`）だけを宣言するとき  
  個数は前の個数の半分より大きい必要がある（整数なら`Qn * 2 > Qp`と同値）。  
  例: 前が「3が9個」→「☆が5個」は可（5 > 4.5）。「☆が4個」は不可。

- 前がSTAR（`Fp = 6`）で、今回が数字（1〜5）に切り替えるとき  
  個数は前のSTARの個数の2倍以上が必要（`Qn` ≥ `2 * Qp`）。例: 前が「☆が2個」→「2が4個」は可。

- 前がSTARで、今回もSTARのとき  
  `Qn` > `Qp`（例: ☆が2個 → ☆が3個）。

実装: `isValidBidAfter(prev, next)`にまとめ、上記の判定式に対してテストする。初手は比較対象がないので、`quantity`>=1かつ`face`が1〜6でよい。

### 2.6 Challenge（ダウト）

直前のBidが言い過ぎだと思ったらダウトと主張する。全員のダイスを卓に出して公開したうえで、2.3の数え方により`A`（実際の個数）を確定させる（2.10）。

- `Q` … 直前のBidの`quantity`
- `F` … 直前のBidの`face`
- `A` … その`F`について2.3で数えた実際の個数（全生存プレイヤーの対象ダイスの合計）

罰則（原作の表に合わせたアプリ仕様）

1. `A < Q`（実際の個数`A`が、宣言の「少なくとも`Q`個」に足りない＝言い過ぎは事実→Challengeした人の見立てが正しい）
   - 宣言者（直前のBidを言った人）が、サイコロ（Q-A）個 失う。

2. `A > Q`（実際には`Q`個より多い＝「少なくとも`Q`個」は真→Challengeした人の見立てが外れた）
   - Challengeした人が、サイコロ（A-Q）個失う。

3. `A == Q`（ちょうど宣言どおり）
   - 宣言者以外の、まだ卓にいる全員が、サイコロ1個ずつ失う（Challengeした人も含む）。

複数個・複数人からサイコロを失わせるとき

- 1人がN個失う: その人の`dice`からN個除く（順序は任意。実装では末尾から除くか、ランダムピックで除く）。
- Nがその人の所持数より大きい: 持っている分だけ除き0個になったらeliminated。超過分は誰にも課さない（または発生しないようテストする）。

Challengeが終わったら、上記の罰を全て適用した上で2.7に入る。

### 2.7 ラウンドが終わったあと（Challenge直後の共通処理）

Challengeで罰が終わったあと、次を行う（`resolveRound`と実装してよい）。

- サイコロ0個 になった人はeliminated
- 生存者が1人だけなら ゲーム終了（2.8）
- そうでなければ、卓上に出していたダイスを手元に戻したうえで、生存者だけ今の個数のままダイスを振り直す（実装上は同じ`dice`リストを振り直しで置き換えてよい）
- `currentBid`はなし、新しいラウンド
- 次にBidを言い出す人は、2.9「新しいラウンドの最初の番」で決める

### 2.8 勝ち

生き残りが1人になったらゲーム終了。その人が勝ち。`state = FINISHED`。  
対CPUのときも同じで、最後に残ったのが人間なら人間の勝ち、CPUだけ残ったら負け。

### 2.9 番の回り方

いつも（ラウンドの途中）

- 時計回り（`players`のリストの順）
- 脱落した人は飛ばす

新しいラウンドの最初の番（直前にChallengeが解決されたあとだけ決める）

`Q`・`A`は2.6と同じ。宣言者 = 直前のBidの`playerId`、Challengeした人 = メソッド`challenge(playerId)`の`playerId`（APIでは`type: "CHALLENGE"`）。

1. `A < Q` … 次のラウンドで最初にBidするのは宣言者。宣言者がeliminatedなら、リストの順で次の生存者。
2. `A > Q` … 最初にBidするのはChallengeした人。eliminatedなら次の生存者。
3. `A == Q` … 最初にBidするのは宣言者（このケースでは宣言者は罰を受けないため）。いなければ次の生存者。

この「最初の人」を`currentPlayerIndex`に反映してから、通常の時計回りに戻る。

### 2.10 卓上での公開とラウンド間の振り直し

原作では次の流れになる。サーバ側のデータモデルは、卓用に別リストを必須にしなくてよい（各プレイヤーの`dice`が手元と卓上の両方の正本になりうる）。

1. Bidのラウンド中  
   各プレイヤーの出目は手元にあり、他者からは見えない（2.2）。このときのBidは、見えない相手の出目を推測したうえでの宣言になる。

2. **Challenge（`CHALLENGE`）が宣言されたら**
   全員のダイスを卓に出して表向きにする（全員の出目が公開される）。`A`は、この公開された集合に対して2.3の数え方で求める。

3. 2.6の罰と2.7の処理が終わったら  
   卓上のダイスを手元に戻し、生存者はそれぞれ今の所持数のとおりダイスを振り直す。新しいラウンドでは再び、他者の出目は非公開（2.2）に戻る。

---

## 3. ゲームの状態（`GameState`）

```text
WAITING   … まだ始まっていない（卓の準備・CPUを並べる前後を含む）
PLAYING   … プレイ中
FINISHED  … 終わった（勝者が決まった）
```

メモ: 「ラウンド終了だけ」の状態は作らない（作ると実装がややこしくなるので）。

---

## 4. 面の数字（`face`）の決まり

APIやプログラムの中では、`face`は次の整数で表す。

| 値       | 意味                                 |
| -------- | ------------------------------------ |
| `1`〜`5` | その数字の面                         |
| `6`      | STAR（宣言でも「STAR」として言える） |

数字1〜5の大小は通常どおり。STARは`6`で表す。

次のBidが前の宣言のあとに許されるかは2章2.5（数字だけの単純比較では足りない。☆のときは半分・2倍の条件がある）。

---

## 5. アクションの種類（`ActionType`）

次の名前をコードに入れてよい。

```text
JOIN, START, BID, CHALLENGE
```

ルール上のChallengeは、HTTPでも`"type": "CHALLENGE"`とそのまま書く（1章1.2）。

HTTPとの対応

- `JOIN`と`START`は、`POST .../join`と`POST .../start`という別のURLで扱う
- `POST .../action`のJSONでは`BID`と`CHALLENGE`だけを受け付ける（`JOIN`や`START`が来たら400で断ってよい）

---

## 6. ゲームのデータ（クラス）

### 6.1 `Game`（ゲーム全体）

| 名前                     | 型                   | 説明                                                                                                                     |
| ------------------------ | -------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `id`                     | String               | ゲームID（例: UUID）                                                                                                     |
| `state`                  | GameState            | 3章のどれか                                                                                                              |
| `players`                | List\<Player\>       | 参加した順。時計回りはこの順                                                                                             |
| `currentPlayerIndex`     | int                  | いま番の人（`players`の何番目か）。脱落は`nextTurn`で飛ばす                                                              |
| `currentBid`             | Bidまたはなし        | 卓に出ている直近の宣言。ラウンド開始直後はなし                                                                           |
| `lastBidPlayerId`        | Stringまたはなし     | いちばん最後にBidした人（Challengeの罰・次ラウンド先攻で必要）                                                           |
| `hostPlayerId`           | String               | 部屋を作った人（ホスト）のプレイヤーID                                                                                   |
| `pendingOpeningPlayerId` | Stringまたはなし     | Challenge直後だけ使う。次ラウンドの先攻のプレイヤーID（2.9）。`resolveRound`で`currentPlayerIndex`に反映したらnullに戻す |
| `actionLog`              | List\<TurnLogEntry\> | 全ラウンドの行動履歴（ゲーム開始から蓄積。6.5参照）                                                                      |
| `currentRound`           | int                  | 現在のラウンド番号（1始まり。`resolveRound`でゲームが続く場合に+1する）                                                  |

### 6.2 `Player`（一人）

| 名前         | 型              | 説明                                                             |
| ------------ | --------------- | ---------------------------------------------------------------- |
| `id`         | String          | プレイヤーID（サーバが発行するのがおすすめ）                     |
| `name`       | String          | 表示名（CPUなら`"CPU 1"`などでもよい）                           |
| `cpu`        | boolean         | trueならCPU（HTTPの`/action`では動かさず、サーバ内で手を決める） |
| `dice`       | List\<Integer\> | 持っている各ダイスの面（4章の数字。STARは`6`）                   |
| `eliminated` | boolean         | trueなら脱落                                                     |

### 6.3 `Bid`（宣言の内容）

| 名前       | 型     | 説明             |
| ---------- | ------ | ---------------- |
| `quantity` | int    | 何個あると言うか |
| `face`     | int    | 4章の数字        |
| `playerId` | String | 宣言した人       |

### 6.4 `Action`（`/action`から渡す内容）

| 名前       | 型         | 説明               |
| ---------- | ---------- | ------------------ |
| `type`     | ActionType | `BID`か`CHALLENGE` |
| `playerId` | String     | 動かす人           |
| `quantity` | Integer    | `BID`のとき必須    |
| `face`     | Integer    | `BID`のとき必須    |

### 6.5 `TurnLogEntry`（行動履歴の1エントリ）

ゲーム中の全アクション（人間・CPU問わず）を時系列で記録するためのデータ。`Game`の`actionLog`に蓄積する。

| 名前                 | 型                | 説明                                                               |
| -------------------- | ----------------- | ------------------------------------------------------------------ |
| `round`              | int               | ラウンド番号（1始まり）                                            |
| `playerId`           | String            | 行動したプレイヤーのID                                             |
| `playerName`         | String            | 表示名（フロント側の利便性のため保持する）                         |
| `type`               | String            | `"BID"` / `"CHALLENGE"` / `"ROUND_START"`                          |
| `quantity`           | Integerまたはなし | BID時のみ                                                          |
| `face`               | Integerまたはなし | BID時のみ                                                          |
| `actualCount`        | Integerまたはなし | CHALLENGE時: 実際の個数 A（2.3の数え方で求めた値）                 |
| `challengeResult`    | Stringまたはなし  | CHALLENGE時: `"BIDDER_LOSES"`/`"CHALLENGER_LOSES"`/`"EXACT_MATCH"` |
| `penaltyDescription` | Stringまたはなし  | CHALLENGE時の罰の説明（例: "CPU 1 がダイス2個失う"）               |

`ROUND_START`は新しいラウンドの開始を示すマーカー。ラウンド番号を区切りとして表示するために使う。`playerId`/`playerName`にはそのラウンドの先攻プレイヤーを入れる。

### 6.6 CPUについて（サーバ側）

- `cpu == true`のプレイヤーは、クライアントが`/action`で送らない。サーバが現在のルールに従って`bid`/`challenge`を呼ぶ。
- CPUの賢さは実装任せ。Bidは2章2.5を満たすものから選ぶ。Challengeしたあとの罰は2章2.6（`A`と`Q`）。
- 人間の手のあと、番がCPUのあいだはサーバ内で`bid`/`challenge`を進める。状態の確認は`GET /games/{id}`でよい。

---

## 7. ゲームの処理（ルールどおり動かす）

ルールを破ったときは例外（例: `IllegalStateException`）にしてよい。Webの返すコード（404など）は、サービス側でこの例外から変える。

### 7.1 `join(String playerId, String name)`（参加）

対CPU専用にするとき: 人間は作った人1人だけでよいので、このメソッドは使わなくてよい（`POST /games`で人間とCPUをまとめて用意するなら不要）。

使う場合（将来PvPに戻す・複数人を想定する場合）

できる条件

- `state`が`WAITING`
- まだ6人未満

するとどうなるか

- `players`に一人追加（`dice`は空でもよい。`start`で振る）

メモ: `playerId`はサーバが発行して、クライアントに返す（8章）。

### 7.2 `start(String playerId)`（開始）

できる条件

- `playerId`が ホストと同じ
- 2人以上いる（対CPUなら人間1 +CPUが1人以上でよい）
- `state`が`WAITING`

するとどうなるか

- `state`を`PLAYING`にする
- 全員に、持っている個数ぶん`dice`を振る（1〜5と6をランダム）
- `currentPlayerIndex = 0`（始まった直後は全員いるので0でよい）
- `currentBid`なし、`lastBidPlayerId`もなし、`pendingOpeningPlayerId`もなし
- `currentRound = 1`、`actionLog`をクリア

### 7.3 `bid(String playerId, int quantity, int face)`（Bid）

できる条件

- `state`が`PLAYING`
- いま番の人が`playerId`（CPUの番ならHTTPでは呼ばず、サーバ内だけで同じメソッドを呼ぶ）
- `currentBid`が まだない（初手）なら、比べる相手がいないのでそのままOK
- `currentBid`があるなら、2章2.5のとおり 続けて許されるBidであること

するとどうなるか

- `currentBid`を更新
- `lastBidPlayerId`を`playerId`にする
- `actionLog`にBIDエントリを追加（`round`は`currentRound`、`type`は`"BID"`、`quantity`と`face`を記録）
- `nextTurn()`する

### 7.4 `challenge(String playerId)`（Challenge）

できる条件

- `state`が`PLAYING`
- いま番の人が`playerId`（CPUも同様）
- `currentBid`がある

やること（2.6と同じ分岐。ここを実装の正とする）

1. `Q` = `currentBid.quantity`、`F` = `currentBid.face`、宣言者ID = `lastBidPlayerId`（`currentBid.playerId`と一致させてよい）
2. 生存プレイヤー全員の`dice`について、2.3で`A`（`F`とSTARの個数の合計） を求める
3. 罰の適用（複数人・複数個ありうる）:
   - `A < Q`: 宣言者から`removeDice(宣言者, Q - A)`（2.6）
   - `A > Q`: Challengeした人（`playerId`）から`removeDice(..., A - Q)`
   - `A == Q`: 宣言者以外の生存者ひとりずつ`removeDice(..., 1)`
4. 罰を入れた直後の生存状態で、2章2.9に従い`pendingOpeningPlayerId`を決める（先攻になりたいプレイヤーのIDを1つ）:
   - `A < Q` … 基本は宣言者。宣言者がeliminatedなら、リスト順で宣言者の次の生存者
   - `A > Q` … 基本はChallengeした人。eliminatedなら次の生存者
   - `A == Q` … 基本は宣言者（この分岐では宣言者は罰を受けない）。いなければ次の生存者
5. `actionLog`にCHALLENGEエントリを追加（`round`は`currentRound`、`type`は`"CHALLENGE"`、`actualCount`にAの値、`challengeResult`に`"BIDDER_LOSES"`/`"CHALLENGER_LOSES"`/`"EXACT_MATCH"`、`penaltyDescription`に罰の説明文を記録）
6. `resolveRound()`を呼ぶ

`removeDice(Player p, int n)`（メソッド化推奨）

- `n`個、実際には`min(n, p.dice.size())`個を`dice`から除く
- 0個になったら`eliminated = true`

### 7.5 `resolveRound()`（ラウンドを片付ける）

`challenge`の直後に呼ぶ想定（Bidだけのラウンドではない。常にChallengeのあとにまとめてよい）。

順番

1. 脱落処理: `eliminated`の扱い（リストから消すかマークのままかは実装でよい。`currentPlayerIndex`とズレないように注意）
2. 勝利判定: 生存者が1人だけなら`state = FINISHED`、`pendingOpeningPlayerId`をnullにして終了
3. 生存者が2人以上なら:
   - 生存者だけ 今の個数のままダイスを振り直す
   - `currentBid`なし、`lastBidPlayerId`なし
   - `state`は`PLAYING`
   - `pendingOpeningPlayerId`を`players`のインデックスに直して`currentPlayerIndex`に書き込み、そのあと`pendingOpeningPlayerId = null`
   - IDが見つからない場合は最初の生存者のインデックスにする
   - `currentRound`を+1し、`actionLog`に`ROUND_START`エントリを追加（先攻プレイヤーの情報を入れる）

次ラウンド先攻のルールの文章は2章2.9（`pendingOpeningPlayerId`はその実装用の持ち場）。

### 7.6 `nextTurn()`（次の番へ）

するとどうなるか

- `currentPlayerIndex`を時計回りに進める（脱落は飛ばす）
- 実装は「次のインデックスへ進め、脱落ならまた進める」か「生きている人数で割った余り」など、よい方法で

---

## 8. REST API（URLとJSON）

共通:

- `Content-Type: application/json`
- 文字コードはUTF-8

### 8.1 ゲームを作る

`POST /games`

送るJSON（対CPU）

```json
{
  "name": "player",
  "cpuCount": 2
}
```

- `name` … 人間プレイヤーの表示名（省略可ならサーバでデフォルトでもよい）
- `cpuCount` … CPUの人数。1〜5の整数から選ぶ。人間1 + `cpuCount`で卓は2〜6人になる（例: `cpuCount`が2なら人間1 +CPU 2で計3人）。
- `cpuCount`を省略したときの扱い（常に1、または400で必須、など）は実装でそろえる。範囲外（0、6以上、小数、文字列など）は400で拒否する。
- 作った時点で`players`に人間1人 + `cpuCount`人のCPUを入れる（各CPUは`cpu: true`、名前は`"CPU 1"`のように区別できる形でよい）。

返るJSON（`201 Created`のイメージ）

```json
{
  "gameId": "uuid",
  "playerId": "uuid"
}
```

- 人間側の`playerId`（ホスト）を返す
- アプリは返ってきた`playerId`を保存して、あとで`start`や`action`に使う

### 8.2 参加する（任意）

`POST /games/{gameId}/join`

対CPUだけなら、8.1で人間とCPUが揃うので、このAPIは使わなくてよい。

送るJSON

```json
{
  "name": "player"
}
```

返るJSON（`200 OK`）

```json
{
  "playerId": "uuid"
}
```

### 8.3 始める

`POST /games/{gameId}/start`

送るJSON

```json
{
  "playerId": "uuid"
}
```

返る`204 No Content`か`200 OK`（中身なしでよい）

### 8.4 Bid / Challenge

`POST /games/{gameId}/action`

人間の番のときだけ送る。CPUの番はサーバがChallengeするので、クライアントは送らない（送っても409 / 400でよい）。

Bidの例

```json
{
  "type": "BID",
  "playerId": "uuid",
  "quantity": 3,
  "face": 5
}
```

Challengeの例

```json
{
  "type": "CHALLENGE",
  "playerId": "uuid"
}
```

返る`204`か`200`（中身なしでよい）

### 8.5 ゲームの状態を取得

`GET /games/{gameId}`

メモ: 終了後にメモリから消している場合（10章）、もう存在しないIDなら404になる。

任意のクエリ: `viewerPlayerId` — 付けたとき、その人本人だけの自分の出目を`myDice`で返す。

注意: ログインなしの想定なので、IDを知っていれば別人のふりができる（本番では別の認証が必要）。

返るJSONの例（`200 OK`）

```json
{
  "id": "uuid",
  "state": "PLAYING",
  "players": [
    {
      "id": "uuid",
      "name": "player",
      "cpu": false,
      "diceCount": 5,
      "eliminated": false
    }
  ],
  "currentPlayer": "uuid",
  "currentBid": {
    "quantity": 3,
    "face": 5,
    "playerId": "uuid"
  },
  "hostPlayerId": "uuid",
  "winnerPlayerId": null,
  "myDice": null,
  "actionLog": [
    {
      "round": 1,
      "playerId": "uuid",
      "playerName": "CPU 1",
      "type": "BID",
      "quantity": 3,
      "face": 5,
      "actualCount": null,
      "challengeResult": null,
      "penaltyDescription": null
    },
    {
      "round": 1,
      "playerId": "uuid",
      "playerName": "CPU 2",
      "type": "CHALLENGE",
      "quantity": null,
      "face": null,
      "actualCount": 2,
      "challengeResult": "BIDDER_LOSES",
      "penaltyDescription": "CPU 1 がダイス1個失う"
    },
    {
      "round": 2,
      "playerId": "uuid",
      "playerName": "CPU 1",
      "type": "ROUND_START",
      "quantity": null,
      "face": null,
      "actualCount": null,
      "challengeResult": null,
      "penaltyDescription": null
    }
  ]
}
```

- `state`が`FINISHED`のとき: `winnerPlayerId`に勝者ID。`currentPlayer`はnullでよい
- `viewerPlayerId`が正しく、その人がいるとき: `myDice`に配列（例`[1, 6, 5]`の`6`はSTAR）
- 各プレイヤーに`cpu`（boolean）を含めてよい（UIで「相手はCPU」と表示する）
- Challengeの罰の前後など、2章2.10の「卓に全員の出目を出した」区間では、各プレイヤーの`dice`の中身を返してよい。通常のBid中は2.2どおり他者の出目は非公開でよい（どちらの状態かは実装で区別できるようにする）

### 8.6 ゲーム一覧

`GET /games`

返るJSON（`200 OK`）— 一覧は短い情報でよい例:

```json
[
  {
    "id": "uuid",
    "state": "WAITING",
    "playerCount": 2
  }
]
```

---

## 9. DTO（Javaのクラス名の例）

| クラス名               | 何に使うか                                                  |
| ---------------------- | ----------------------------------------------------------- |
| `CreateGameRequest`    | `POST /games`の本文（`name`, `cpuCount`（1〜5の整数）など） |
| `CreateGameResponse`   | `gameId`, `playerId`                                        |
| `JoinRequest`          | `name`                                                      |
| `JoinResponse`         | `playerId`                                                  |
| `StartRequest`         | `playerId`                                                  |
| `ActionRequest`        | `type`, `playerId`, `quantity`, `face`                      |
| `GameResponse`など     | `GET`の返却（8.5）。`actionLog`を含む                       |
| `TurnLogEntryResponse` | `actionLog`の各エントリ（6.5の`TurnLogEntry`に対応）        |

---

## 10. 状態の持ち方（メモリのみ・DBは使わない）

この仕様ではDBにゲームを保存しない。サーバのメモリ（プロセスの中）だけに`Game`を持つ。

### 10.1 方針

- 進行中のゲームは`Map`など（例: ゲームID → `Game`）で保持する
- `state`が`FINISHED`になっても、すぐにMapから削除しない。クライアントが最終状態（勝敗結果や`actionLog`）を`GET`で取得できるように、`FINISHED`のゲームはメモリに残す。クライアントが明示的に新しいゲームを作り直すか、サーバ再起動で消える想定でよい
- サーバを再起動するとメモリは消えるので、進行中の卓も全部なくなる（この範囲の仕様ではそれでよい）

### 10.2 リポジトリでやること（メソッドの形）

サービス層からは「保存・取得」が分かるように、次のようなインターフェースを用意してよい。中身はメモリのMapで実装する（JPAやテーブルは不要）。

```text
Game findById(String id);
void save(Game game);
void deleteById(String id);   // FINISHED のときに呼ぶ想定
List<Game> findAll();
```

`save`は「新規も更新も上書きでよい」程度の意味で使う。

### 10.3 あとからDBを足す場合

別プロジェクトや将来の話として、同じ`GameRepository`の裏をDBに差し替えることはできる。が、今回の仕様の前提ではDBは書かない。

---

## 11. フォルダの分け方（例）

```text
src/main/java/com/bluff/
  controller/   GameController など（URLを受ける）
  service/      GameService（処理の流れ）
  repository/   GameRepositoryと、メモリに持つ実装（Map）
  entity/       Game, Player, Bid, Action, GameState, ActionType
  dto/          送受信用の型
  cpu/          CPUの手を決めるクラス（任意。名前はチームでよい）
```

---

## 12. エラー時のHTTPコード（目安）

| とき                                            | コード                       |
| ----------------------------------------------- | ---------------------------- |
| ゲームがない                                    | 404                          |
| ルール違反・番じゃない・今の状態ではできない    | 409か400（チームでそろえる） |
| JSONの形がおかしい                              | 400                          |
| `POST /games`の`cpuCount`が1〜5の整数として無効 | 400                          |

---

## 13. 実装する順番（おすすめ）

1. `Game`（`pendingOpeningPlayerId`含む）/ `Player` / `Bid`と、`bid` / `challenge`（`A`と`Q`の三種＋`removeDice`） / `resolveRound` / `nextTurn`を小さなテストで確かめる（`A == Q`で全員（宣言者以外）が1個も忘れずに）
2. メモリ用の`GameRepository`（Map）と`GameService`（`FINISHED`で`deleteById`、CPU手番の処理など）
3. CPUが合法手を選ぶ処理（まずは簡単でよい）
4. `GameController`とDTO
5. 手動または自動でAPIを通して確認

---

## 14. 仕様を変えたとき

仕様を変えたら、日付と何を変えたかを1行ここかGitのコミットに残すと、みんなで食い違いが減る。

- 2026-03-27: 行動履歴（`actionLog`）を追加。`TurnLogEntry`（6.5）、`Game`に`actionLog`/`currentRound`（6.1）、`bid`/`challenge`/`resolveRound`/`start`でのログ記録（7章）、APIレスポンスに`actionLog`（8.5）、DTO追加（9章）、FINISHED時の即時削除をやめた（10.1）。
