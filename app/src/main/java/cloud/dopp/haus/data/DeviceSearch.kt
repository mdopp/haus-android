package cloud.dopp.haus.data

/**
 * Which devices the app-icon menu may offer (#100): Haus offers cameras in the
 * addable list too, but draws no card for one ([Napi.CARD_DOMAINS]).
 */
object DeviceSearch {

    /** The devices Haus draws a card for. */
    fun pickable(devices: List<Device>): List<Device> = devices.filter { it.domain in Napi.CARD_DOMAINS }
}
