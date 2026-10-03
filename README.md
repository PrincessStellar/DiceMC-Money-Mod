# Money and Sign Shops

[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-2ea44f)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-26.1.2.109%2B-e07a2f)](https://neoforged.net/)
[![Java](https://img.shields.io/badge/Java-25-1f6feb)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-f1c40f)](LICENSE.txt)

Player balances and chest shops made with wall signs.

The original mod is **Copyright (c) 2023 Caltinor**, under the MIT License.
This tree is the **NeoForge 26.1.2 port by PrincessStellar**. The port is under the same MIT License. See [Copyright and license](#copyright-and-license).

| | |
| --- | --- |
| Mod id | `dicemcmm` |
| Version | `2.0.0` |
| Name | Money and Sign Shops |
| Loader | NeoForge `26.1.2.109` or newer |
| Game | Minecraft `26.1.2` |
| Java | 25 |

## Contents

- [Install](#install)
- [Languages](#languages)
- [Commands](#commands)
- [Player shops](#player-shops)
- [Server shops](#server-shops)
- [What a shop blocks](#what-a-shop-blocks)
- [Offer updates](#offer-updates)
- [Containers](#containers)
- [FTB Chunks](#ftb-chunks)
- [FTB Quests reward](#ftb-quests-reward)
- [Server config](#server-config)
- [KubeJS](#kubejs)
- [For other mods](#for-other-mods)
- [Build from source](#build-from-source)
- [Copyright and license](#copyright-and-license)

## Install

Put `dicemcmm-26.1.2-2.0.0.jar` in the server `mods` folder. The version inside that jar is `2.0.0`. The world stores balances. The mod does not need a database for normal play.

> [!NOTE]
> The server writes every money sentence. A player does not need this mod, a resource pack, or a custom payload. The server reads the language the vanilla client already sent. `pt_br` uses `pt_br.json`. Every other language uses `en_us.json`. A missing Portuguese line uses the English line. Item names stay item components, so the client still translates the item and shows its hover. The sign words and the typed command names stay exactly as written. The server log stays English.

There is no separate permission-node mod. Levels below are Minecraft operator levels, from 0 to 4. A configured level means **that level or higher**. Level 0 is everyone.

## Languages

| File | Role |
| --- | --- |
| `assets/dicemcmm/lang/en_us.json` | English. This is the default. |
| `assets/dicemcmm/lang/pt_br.json` | Portuguese (Brazil). |

The filename is `pt_br.json`, never `pt_BR.json`. The server picks the file from the language the client already reported. Player names, amounts, and item names stay inside the sentence. A missing Portuguese line uses the English line. The player never sees a raw translation key.

The four lines written on a sign are stored as text. They do not switch language after the shop is activated. The shop words the mod accepts are English and must be typed as shown below.

Console logs and the optional history notes stay English. Players do not see those lines in chat.

## Commands

`/money`, `/money transfer`, and `/shop builder` must be typed by a player. `/top` and `/money admin` can also be typed from the server console. The console counts as an operator.

### Everyone

| Command | What it does |
| --- | --- |
| `/money` | Shows your balance. On join, the same line is sent in chat. The first time your balance is read, the account is created with `starting_funds`. |
| `/money transfer <amount> <name>` | Moves money from you to that player. `<amount>` is 0 or more. `<name>` is one word, and the player must already be known to the server. |
| `/top` | Lists the richest players. The length comes from `top_size` (default 3). `0` prints the header and no rows. |
| `/shop builder` | Writes the held book and quill so a shop can read it. See [Shop builder](#shop-builder). |

> [!IMPORTANT]
> A transfer of `0`, or a transfer from an account back to itself, does not change the balance. The command still reports that the transfer went through. A transfer larger than the sender's balance is refused. Money is not created by sending it to yourself.

Examples:

```text
/money
/money transfer 50 Notch
/top
```

### Admin

`/money admin` requires `admin_level` (default **2**).

`byName` looks up saved names. Those players do not have to be online. Each name is one word.

`online` uses players who are in the world. The first player argument can be one name or a selector such as `@p` or `@a`. Extra names after the amount, or after the last transfer target, must also be online.

`<action>` is only `set`, `give`, or `take`. Any other word is refused.

`<amount>` is 0 or more. A value that is not a real number is refused before anyone is paid.

The command checks `admin_level` once. Each name is still checked. A missing name is reported and skipped. A repeated name is reported and is not paid twice. `set`, `give`, `take`, and `balance` still apply to the other names. A transfer is different: the source must cover every distinct other player at once. If the source cannot, nobody in that command is paid.

More than 100 names changes nothing.

| Command | Effect |
| --- | --- |
| `/money admin byName balance <name> [more names]` | Shows each balance. |
| `/money admin byName <action> <name> <amount> [more names]` | Applies `set`, `give`, or `take` to each name. |
| `/money admin byName transfer <amount> <from> <to> [more names]` | Moves `<amount>` from `<from>` to each other name. |
| `/money admin online balance <players> [more names]` | Same, for online players. |
| `/money admin online <action> <players> <amount> [more names]` | Same, for online players. |
| `/money admin online transfer <amount> <from> <players> [more names]` | `<from>` is one online player. `<players>` can be a selector. |

```text
/money admin byName give Steve 100 Alex
/money admin online take @a 25
/money admin byName transfer 40 Alex Steve Bob
/money admin online balance @a
```

`give Steve 100 Alex` adds 100 to Steve and 100 to Alex. The amount stays in the same place as the one-player command. Extra names come after it.

A transfer to the source account does not change that balance. A transfer of `0` does not move money.

> [!WARNING]
> `take` does not stop at zero. An admin can leave a negative balance. A shop owned by that player will then refuse a sale it cannot pay.

## Player shops

A player shop needs a wall sign on a vanilla block that exposes an item inventory. A standing sign is ignored. The containers this version accepts are listed under [Containers](#containers).

`[buy]` and `[sell]` require `shop_level`. The default is **0**, so every player can create them.

### Write the sign

Right-click a blank wall sign and fill all four lines. The top line is line 1. The bottom line is line 4.

| Line | Text |
| --- | --- |
| 1 | `[buy]` or `[sell]` |
| 2 | Any note you want. It is kept. |
| 3 | Any note you want. It is kept. |
| 4 | The price. Use a plain number. Use `.` as the decimal separator. |

The word on line 1 is accepted in any letter case. These four tokens are the only ones:

```text
[buy]
[sell]
[server-buy]
[server-sell]
```

A minus sign is stored as a positive price. `-50` becomes `50`. If line 4 is not a real number, the sign is broken and dropped, and nothing is activated.

Put the trade items in the container **before** you activate the sign. Every non-empty slot is saved. The player pays the price once and moves that whole set.

Right-click the sign to activate it. Line 1 stays blue. Line 4 is replaced by the formatted price in gold. Lines 2 and 3 stay as you wrote them.

```text
[buy]
diamonds
64
50
```

That sign sells whatever is in the container. It does not read the words "diamonds" or "64". Those lines are only a label. The items come from the container.

### Use the shop

| Action | Result |
| --- | --- |
| Left-click the sign | Shows the price in chat. The sentence does not name the items. |
| Right-click a `[buy]` sign | You pay the owner and receive the items. The items are taken from the container. |
| Right-click a `[sell]` sign | You give the items and the owner pays you. The items must fit in the container. |

A `[buy]` purchase is also announced to the server in the third person, so other players do not see "you bought". If your inventory cannot hold the items, the rest drops at your feet. `[server-buy]` tells only the buyer.

A purchase is one green sentence and does not name the items. A chest uses the word chest. A barrel uses the word barrel. In Portuguese those words are baú and barril. A completed sale, and the purchase announcement, are green for the whole sentence. A failure is one red sentence. Other money lines stay plain. The sign text and a shop book keep whatever was written on them.

Each purchase and sale rereads the container before anything moves. See [Offer updates](#offer-updates). The buy fails when the container does not have the current offer, or when you cannot pay. The sell fails when you do not have the items, when the container is full, or when the owner cannot pay. No money moves on a failed trade.

### One trade from a book

A book and quill can stand in for one item. The first page must start with `vending`, then **one** character (a space is the usual one), then the item data the game can read as an item stack.

```text
vending {id:"minecraft:diamond",count:1}
```

If the page is shorter than that, or the data cannot be read, the shop sells the book itself.

### Shop builder

`/shop builder` writes that same page. It does not invent a second format.

Hold a book and quill in one hand and the item in the other. The book must be a single book, not a stack. The command copies the held item's data, including the count, onto the book's first page. It does not take the item, split the book, or give a new item. Any older writing in that book is replaced.

Put the book in the shop container with the other trade items, then activate the sign. The shop reads the page. If the item data does not fit on one page, or it cannot be read back, the book is left unchanged.

```text
/shop builder
```

## Server shops

`[server-buy]` and `[server-sell]` require `admin_level` (default **2**). Activation still reads the items from the container, so the container cannot be empty.

| Sign | What a customer does |
| --- | --- |
| `[server-buy]` | Pays the price and receives a copy of the saved items. The container is not emptied. The money is removed from the customer. It is not paid to a player. |
| `[server-sell]` | Gives the items and is paid by the server. The items are not inserted into the container. |

Server shops reread the container the same way player shops do. See [Offer updates](#offer-updates). `[server-buy]` still does not empty the container. `[server-sell]` still does not put the sold items into the container.

## Offer updates

You do not remake the sign when the container changes. On a buy or a sell, the shop rereads the container once, then uses that list for the trade. The price on the sign does not change. A new item joins the same purchase for that same price. The sign's words are not rewritten.

| What changed in the container | What the next trade does |
| --- | --- |
| Another stack of an item the shop already sells | That stack is included. Two stacks of 64 stay two stacks of 64. They are not added into one count. |
| More items added onto a stack already in a slot | The container holds one stack. The trade uses that live count, not the count stored when the sign was made. |
| A new item type | It is added on the next trade, at the same price, without remaking the sign. Each slot of that item is its own stack. Those stacks are not added together. |
| An item removed completely | That item is dropped from the purchase. It is not required anymore. |
| A stack smaller than the count stored when the sign was made | The trade uses the stack that is there. It does not fail because the stack is smaller. |
| Nothing left | The trade fails for stock. Nothing is paid and nothing is given. |

A vending book is read the same way as at activation. When the page describes an item, the shop uses that item and does not sell the book. Both halves of a double chest are included. The same rules apply to a chest, a trapped chest, a barrel, and every copper chest.

Stacks of the same item stay separate. A shop that holds 64, 64, and 32 gives 64, 64, and 32. It does not add those counts into one stack. The same rule applies to any other combination.

`[server-buy]` gives copies of the updated offer and leaves the container alone, so a new item stays in the container and is included again on the next purchase. `[server-sell]` takes the updated offer from the player and does not insert it. A player `[buy]` takes the offer counts from the container. A player `[sell]` takes them from the player and inserts them.

The reread happens before items or money move, and it does not run again during that trade. A failed trade does not pay. A player shop takes the items from the container. It does not create a second copy. A server buy creates the items and leaves the container as it is.

## What a shop blocks

These rules are part of normal use. They are here so a shop is not emptied by accident.

| Action | Who can do it |
| --- | --- |
| Activate a sign on a container that is already a shop | Only the player who already owns that container. Another player, including an admin, cannot take it over. |
| Open the shop container | The owner, or someone with `admin_level`. |
| Break the shop container | Someone with `admin_level`. |
| Break the activated wall sign | The owner, or someone with `admin_level`. |
| Clear the shop mark by breaking the sign | Only the owner. An admin who is not the owner can break the sign and leave the container marked. |

Also:

- A price that is not a real number, including one that is not finite, is rejected.
- An explosion does not destroy the shop container or an activated wall sign.
- A piston does not push or destroy those blocks.
- A new block cannot be placed directly beside the shop container.
- A hopper, or a hopper minecart, does not pull items down out of the shop container.

> [!WARNING]
> A modded pipe that takes items through the inventory directly is not blocked. Vanilla hoppers under the container are blocked. The mod does not claim to stop every other transport block.

## Containers

A shop reads the block's item inventory. The same activation, price, stock, and protection checks apply to every container below. There is no separate path for one type.

| Block | Shop |
| --- | --- |
| Chest | Yes, including a double chest. Both halves are protected. |
| Trapped chest | Yes, including a double trapped chest. Both halves are protected. |
| Barrel | Yes. |
| Copper chest | Yes. Every weathering stage, and the waxed form of each stage. A double copper chest protects both halves. |

These are not shops:

- An ender chest. That inventory is private to the player who opens it.
- A chest boat or a chest minecart. Those are entities, not the block behind a wall sign.
- A container added by another mod.

## FTB Chunks

FTB Teams and FTB Chunks on the Minecraft 26.1.2 line were checked before this was added. Protection is both the claimed chunk and the team privacy for block interaction and block editing. FTB Teams does not cancel a block click by itself. FTB Chunks asks the team, then cancels the right-click.

If FTB Chunks `26.1.2` or newer is installed, a player who fails that check can still right-click an **already activated** shop sign and buy or sell. This covers player shops and server shops. The mod asks FTB Chunks whether that click is denied. It uses the same two checks Chunks uses for a right-click: block interaction, and block editing when the held item is a block.

The click stays denied for the block itself.

- The sign is not opened or edited.
- A held block is not placed on the sign.
- The sign cannot be broken from this click.
- The chest or barrel cannot be broken or opened.
- No other block in the claim is opened.

Creating a shop still requires the claim to allow the click. A sign that is not activated yet does not become a shop for someone the claim rejects. If some other mod canceled the click and FTB Chunks would have allowed it, the shop does not run. If the Chunks check cannot be read, the click stays blocked.

FTB Chunks is optional. Without it, and without FTB Teams, balances and sign shops still work. This jar does not include either mod. This behavior has not been tried on a running server.

## FTB Quests reward

If FTB Quests `26.1.2.8` or newer is installed, the mod adds one reward type:

| | |
| --- | --- |
| Id | `dicemcmm:moneyreward` |
| English name | Money reward |
| Portuguese name | Recompensa em dinheiro |
| Amount field | Amount to give / Quantia a dar |
| Icon | `dicemcmm:textures/moneybag.png` |

The amount must be greater than zero. Zero, a negative number, or a number that is not finite pays nothing and does not print a chat line. A valid claim adds the money and tells that player what they received.

FTB Quests is optional. Without it, balances and sign shops still work. This jar does not include FTB Quests.

## Server config

NeoForge writes the server config for mod id `dicemcmm` as `config/dicemcmm-server.toml`. The tables are top level. Keys are snake case. Defaults:

```toml
[money]
starting_funds = 1000

[display]
currency_symbol = "$"
currency_symbol_on_left = true

[permissions]
admin_level = 2
shop_level = 0

[death]
loss_on_death = 0.0

[ranking]
top_size = 3

[history]
enable_history = false
```

Comments in that file are short Portuguese. Ranged keys also get NeoForge's own default and range lines. Those ranges are plain digits: `starting_funds` is 0 to 1000000000, `top_size` is 0 to 100, `admin_level` and `shop_level` are 0 to 4, and `loss_on_death` is 0 to 1. History stays off, and its comment says that it stays off.

| Key | Meaning |
| --- | --- |
| `starting_funds` | Money given the first time an account is created. A negative or non-finite setting is treated as 0. Default `1000`. Range 0 to 1000000000. |
| `currency_symbol` | Text placed beside amounts. Default `$`. |
| `currency_symbol_on_left` | `true` prints `$50`. `false` prints `50$`. |
| `admin_level` | Operator level for `/money admin`, server shops, breaking a shop container, and opening someone else's shop. Default `2`. Range 0 to 4. |
| `shop_level` | Operator level for `[buy]` and `[sell]`. Default `0` (everyone). Range 0 to 4. |
| `loss_on_death` | Fraction of the balance removed on death. `0` is nothing. `0.5` is half. The player is told the amount only when money was actually removed. Range 0 to 1. |
| `top_size` | How many rows `/top` prints. `0` disables the rows. Default `3`. Range 0 to 100. |
| `enable_history` | When `true`, the mod tries to record transactions with H2. Default `false`. It stays off. |

> [!WARNING]
> The history database is off by default, and this jar does not bundle an H2 driver. If you turn history on without that driver, the mod keeps running and skips the record. History text is for the database. It is not player chat.

The server writes the whole sentence in the player's language, then inserts amounts and item components. Changing the symbol does not translate the sentence.

## KubeJS

KubeJS is optional. This jar does not include it, and the shop and the money commands work when it is absent. No KubeJS class loads unless KubeJS itself is loaded. The supported line is KubeJS `26.1.2-8.0.6` (the `2601` branch, Minecraft `26.1.2`). A `2101` / `1.21` KubeJS jar is not this API.

When that KubeJS is installed, server scripts get `DiceMoney` and `DiceMoneyEvents`.

| Call | What it does |
| --- | --- |
| `DiceMoney.getBalance(player)` | Reads a server player's balance. The first read creates the account with `starting_funds`, the same way `/money` does. Anyone else returns `0`. |
| `DiceMoney.give(player, amount)` | Adds a finite amount of 0 or more through the mod's own accounts. A negative or non-finite amount moves nothing. |
| `DiceMoney.take(player, amount)` | Removes a finite amount the player can pay. If they cannot, it returns false and moves nothing. Zero returns true and moves nothing. |

`DiceMoneyEvents.shop(event => {})` runs after the offer is known and before items or money move. `event.cancel()` stops that trade. `event.type` is `[buy]`, `[sell]`, `[server-buy]`, or `[server-sell]`. `event.price` is the sign price. `event.items` is a copy, so the script cannot change the items the shop moves.

`DiceMoneyEvents.command(event => {})` runs before `set`, `give`, `take`, or `transfer` writes a balance. `event.command` is `money`. `event.action` is one of those four words. `event.player` is null when the console typed the command. `event.charge(amount)` takes that amount from the player who typed the command, through `DiceMoney.take`. The console cannot be charged. `event.cancel()` stops the command from writing balances. A charge that already succeeded is not undone by cancel.

`/money` with no arguments, `/top`, and `/shop builder` do not fire that command event.

This mod does not put a price on `/enchant` or on any vanilla command. A pack script can. `examples/charge-command.js` charges 10 to run `/enchant` and cancels the command when the player cannot pay. KubeJS does not load the `examples` folder. Copy that file into `kubejs/server_scripts` on a pack that already has KubeJS. The script sends no sentence of its own.

```javascript
ServerEvents.command('enchant', event => {
  const player = event.parseResults.context.source.player
  if (player == null) return
  if (!DiceMoney.take(player, 10)) event.cancel()
})
```

## For other mods

Other mods can call `dicemc.money.api.MoneyManager` on the server.

```java
MoneyManager.get().getBalance(
    Identifier.fromNamespaceAndPath("dicemcmm", "player"),
    playerId);
```

| Method | Behavior |
| --- | --- |
| `getBalance` | Returns the balance. A missing account is created with the starting funds. A missing or non-finite value returns `0`. |
| `setBalance` | Replaces the balance. Refuses a null id or a non-finite value. |
| `changeBalance` | Adds the value (use a negative value to remove money). Refuses a non-finite value or a non-finite result. |
| `transferFunds` | Moves the absolute amount. Refuses the move when the source cannot pay, or when a result would be non-finite. An amount of `0`, or a transfer to the same account, returns success and does not change the balance. |

Account types:

| Id | Use |
| --- | --- |
| `dicemcmm:player` | A player's balance. This is what the commands and shops use. |
| `dicemcmm:server` | Reserved server account type. Server shops do not pay this account. They add or remove the player's balance directly. |

## Build from source

Use Java 25.

```bash
./gradlew jar
```

The jar is written to `build/libs/dicemcmm-26.1.2-2.0.0.jar`. The version inside the jar is `2.0.0`.

FTB Quests, FTB Chunks, and KubeJS are compile-only optional dependencies. You do not ship them inside this jar. Rhino is also compile-only, because the KubeJS event types reference it, and it is not shipped. The quest reward appears only when FTB Quests `26.1.2.8` or newer is present. The claim exception appears only when FTB Chunks `26.1.2` or newer is present and its protection check can be read. The script API appears only when KubeJS `26.1.2-8.0.0` or newer, and older than `26.2`, is present.

## Copyright and license

| Piece | Holder | Terms |
| --- | --- | --- |
| Original mod | Copyright (c) 2023 Caltinor | MIT License |
| NeoForge 26.1.2 port | Copyright (c) 2026 PrincessStellar | The same MIT License |

The port notice is attribution for the 26.1.2 changes. It does not remove Caltinor's copyright and it does not add a new set of restrictions.

The full grant, the requirement to keep the copyright and permission notices, and the warranty disclaimer are in [LICENSE.txt](LICENSE.txt).

Money and Sign Shops is provided **as is**, without warranty, as the MIT License states.
