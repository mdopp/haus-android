package cloud.dopp.haus.data

/**
 * Every path the app calls on Haus, spelled whole (laib ADR 0022: Haus's
 * `/napi` is this app's API). Each one is a route Haus registers in
 * `internal/napi` and lists in its own auth table; `NapiContractTest` holds
 * the app to that table. All of them want `Authorization: Bearer haus_device_…`.
 */
object Napi {
    /** The house persona: name, greeting, colours, icon path. */
    const val PERSONA = "/napi/persona"
    const val PERSONA_ICON = "/napi/persona/icon.svg"

    /** The Box keys, for the admin widgets. */
    const val BOX_HOME = "/napi/box/home"
    const val BOX_SERVICES = "/napi/box/services"

    /** The house, from Home Assistant. */
    const val HA_CALL = "/napi/ha/call"
    const val STATE = "/napi/portal/state"
    const val ENERGY = "/napi/portal/energy"
    const val HISTORY = "/napi/portal/entity-history"
    const val ACTIVE = "/napi/portal/active"
    const val ADDABLE = "/napi/portal/start/addable"
    fun cameraSnapshot(entityId: String) = "/napi/portal/camera/$entityId/snapshot"

    /** The live stream: which entities this device shows, and their card_state frames. */
    const val WATCH = "/napi/portal/watch"
    const val EVENTS = "/napi/portal/events"

    /** The tool widgets: the catalog, and a row's action. Row paths come from the catalog. */
    const val TOOL_DEFS = "/napi/defs/tool"
    const val ACTION_CALLBACK = "/napi/action-callback"

    /** The camera capture into the share's consume folder. */
    const val UPLOAD = "/napi/upload"

    /**
     * The root every path above shares (`/napi/`). A tool's row path comes from
     * Haus's catalog at run time and must sit under it — see [ToolDefs.napiPath].
     */
    val ROOT: String = PERSONA.substringBefore("persona")
}
