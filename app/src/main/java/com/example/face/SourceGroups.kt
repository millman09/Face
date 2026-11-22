package com.example.face

import android.net.Uri

/** Optional narrower deep searches, each limited to one group of catalogue sources. */
enum class SourceGroup(val title: String, val buttonLabel: String, private val hosts: List<String>) {
    WEBCAM(
        "Webcam sites", "Webcam sites only",
        listOf("chaturbate.com", "cam4.com", "camsoda.com", "stripchat.com", "bongacams.com", "myfreecams.com", "livejasmin.com"),
    ),
    IMAGEBOARDS(
        "Imageboards and image hosts", "Imageboards and image hosts only",
        listOf(
            "4chan.org", "anonibx.com", "motherless.com", "realbooru.com",
            "imagefap.com", "pornpics.com", "xxx.gallery", "nudy.pics", "siss.pics", "pichunter.com",
            "imagetwist.com", "imagebam.com", "imagevenue.com", "jpg.fish",
            "imgur.com", "imgbb.com", "postimages.org", "imgbox.com", "imgchest.com", "catbox.moe", "pixeldrain.com",
            "erome.com", "fapello.com", "socialmediagirls.com", "simpcity.su", "vipergirls.to", "planetsuzy.org",
        ),
    );

    fun contains(source: WebsiteSource): Boolean {
        val host = Uri.parse(source.baseUrl).host?.lowercase()?.removePrefix("www.").orEmpty()
        return hosts.any { host == it || host.endsWith(".$it") }
    }

    companion object {
        fun fromName(name: String?): SourceGroup? = values().firstOrNull { it.name == name }
    }
}
