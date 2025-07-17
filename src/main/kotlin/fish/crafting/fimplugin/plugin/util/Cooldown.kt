package fish.crafting.fimplugin.plugin.util

class Cooldown(val ms: Long) {

    private var lastActivated = 0L

    fun activate(): Boolean {
        val now = System.currentTimeMillis()
        val timePassed = now - lastActivated
        if(timePassed > ms) {
            lastActivated = now
            return true
        }

        return false
    }

}