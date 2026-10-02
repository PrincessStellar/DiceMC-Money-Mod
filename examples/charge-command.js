// Copy this file into kubejs/server_scripts on a pack that already has KubeJS.
// KubeJS does not load this examples folder.
// Money and Sign Shops does not charge for /enchant. This script is only an example.

ServerEvents.command('enchant', event => {
	const player = event.parseResults.context.source.player
	if (player == null) {
		return
	}
	if (!DiceMoney.take(player, 10)) {
		event.cancel()
	}
})
