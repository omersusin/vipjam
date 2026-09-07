package com.vipjam.data

data class TransducerSpec(
    val brand: String,
    val model: String,
    val ddcHint: String,
    val dynamicMode: Int,
)

object TransducerDb {
    private val SPECS = listOf(
        TransducerSpec("CMF", "CMF Buds Pro 2", "generic-iem", 0),
        TransducerSpec("CMF", "CMF Buds Pro", "generic-iem", 0),
        TransducerSpec("CMF", "CMF Buds", "generic-iem", 0),
        TransducerSpec("Nothing", "Ear (2)", "generic-iem", 0),
        TransducerSpec("Nothing", "Ear (a)", "generic-iem", 0),
        TransducerSpec("Nothing", "Ear (1)", "generic-iem", 0),
        TransducerSpec("Sony", "WH-1000XM5", "sony-wh1000x", 4),
        TransducerSpec("Sony", "WH-1000XM4", "sony-wh1000x", 4),
        TransducerSpec("Sony", "WF-1000XM5", "sony-wf1000x", 0),
        TransducerSpec("Sony", "WF-1000XM4", "sony-wf1000x", 0),
        TransducerSpec("Sony", "LinkBuds S", "sony-linkbuds", 0),
        TransducerSpec("Apple", "AirPods Pro 2", "airpods-pro", 0),
        TransducerSpec("Apple", "AirPods Pro", "airpods-pro", 0),
        TransducerSpec("Apple", "AirPods Max", "airpods-max", 4),
        TransducerSpec("Apple", "AirPods 4", "airpods", 1),
        TransducerSpec("Apple", "AirPods 3", "airpods", 1),
        TransducerSpec("Samsung", "Galaxy Buds3 Pro", "buds2-pro", 0),
        TransducerSpec("Samsung", "Galaxy Buds2 Pro", "buds2-pro", 0),
        TransducerSpec("Samsung", "Galaxy Buds FE", "buds-fe", 2),
        TransducerSpec("Sennheiser", "HD 560S", "hd650", 4),
        TransducerSpec("Sennheiser", "HD 650", "hd650", 4),
        TransducerSpec("Sennheiser", "HD 600", "hd600", 4),
        TransducerSpec("Audio-Technica", "ATH-M50x", "m50x", 3),
        TransducerSpec("Audio-Technica", "ATH-M40x", "m40x", 3),
        TransducerSpec("Beyerdynamic", "DT 990 Pro", "dt990", 4),
        TransducerSpec("Beyerdynamic", "DT 770 Pro", "dt770", 3),
        TransducerSpec("Bose", "QuietComfort Ultra", "qc45", 4),
        TransducerSpec("Bose", "QuietComfort 45", "qc45", 4),
        TransducerSpec("Google", "Pixel Buds Pro 2", "generic-iem", 0),
        TransducerSpec("Google", "Pixel Buds Pro", "generic-iem", 0),
        TransducerSpec("Google", "Pixel Buds A-Series", "generic-iem", 2),
    )

    fun resolve(productName: String?): TransducerSpec? {
        val name = productName?.trim().orEmpty()
        if (name.isEmpty()) return null
        return SPECS.filter { spec ->
            name.contains(spec.model, ignoreCase = true)
        }.maxByOrNull { it.model.length }
    }
}
