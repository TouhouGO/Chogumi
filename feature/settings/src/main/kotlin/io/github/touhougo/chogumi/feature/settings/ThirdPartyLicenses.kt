package io.github.touhougo.chogumi.feature.settings

import android.content.Context
import androidx.annotation.StringRes
import io.github.touhougo.chogumi.core.res.R

/**
 * 随 App 分发的许可证。[asset] 是 assets/licenses/ 下随包的原文；没有原文的只给项目地址。
 */
internal enum class LicenseKind(val label: String, val asset: String?) {
    GPL_3("GPL-3.0", "GPL-3.0.txt"),
    APACHE_2("Apache-2.0", "Apache-2.0.txt"),
    MIT("MIT", "MIT.txt"),
    GPL_2_CLASSPATH("GPL-2.0 with Classpath Exception", "GPL-2.0-Classpath-Exception.txt"),
    CC_BY_4("CC-BY-4.0", "CC-BY-4.0.txt"),
}

/** 一项随包第三方内容的许可展示：开源许可走 SPDX，只有版权声明的素材不能用开源许可冒充。 */
internal sealed interface ThirdPartyLicense {
    data class Spdx(val kind: LicenseKind) : ThirdPartyLicense
    data class Notice(@StringRes val textRes: Int) : ThirdPartyLicense
}

/** 一项第三方代码、素材或数据。[id] 也是文档清单表的稳定键。 */
internal data class ThirdPartyNotice(
    val id: String,
    @StringRes val nameRes: Int,
    val license: ThirdPartyLicense,
    val url: String,
)

/**
 * 随 App 分发的第三方库与素材，是 About 与文档「随 APK 分发的第三方清单」表的唯一 catalog。
 * API 快照只存在于源码仓库，不属于这里，也不显示在 About。
 */
internal val BundledThirdPartyNotices: List<ThirdPartyNotice> = listOf(
    ThirdPartyNotice("androidx-jetpack", R.string.about_third_party_androidx, ThirdPartyLicense.Spdx(LicenseKind.APACHE_2), "https://developer.android.com/jetpack"),
    ThirdPartyNotice("kotlin-kotlinx", R.string.about_third_party_kotlin, ThirdPartyLicense.Spdx(LicenseKind.APACHE_2), "https://kotlinlang.org"),
    ThirdPartyNotice("ktor-okhttp-okio", R.string.about_third_party_ktor, ThirdPartyLicense.Spdx(LicenseKind.APACHE_2), "https://ktor.io"),
    ThirdPartyNotice("coil", R.string.about_third_party_coil, ThirdPartyLicense.Spdx(LicenseKind.APACHE_2), "https://coil-kt.github.io/coil/"),
    ThirdPartyNotice("koin", R.string.about_third_party_koin, ThirdPartyLicense.Spdx(LicenseKind.APACHE_2), "https://insert-koin.io"),
    ThirdPartyNotice("kyant-backdrop", R.string.about_third_party_backdrop, ThirdPartyLicense.Spdx(LicenseKind.APACHE_2), "https://github.com/Kyant0/AndroidLiquidGlass"),
    ThirdPartyNotice("material-symbols", R.string.about_third_party_material_symbols, ThirdPartyLicense.Spdx(LicenseKind.APACHE_2), "https://fonts.google.com/icons"),
    ThirdPartyNotice("material-kolor", R.string.about_third_party_material_kolor, ThirdPartyLicense.Spdx(LicenseKind.MIT), "https://github.com/jordond/materialkolor"),
    ThirdPartyNotice("colormath", R.string.about_third_party_colormath, ThirdPartyLicense.Spdx(LicenseKind.MIT), "https://github.com/ajalt/colormath"),
    ThirdPartyNotice("jsoup", R.string.about_third_party_jsoup, ThirdPartyLicense.Spdx(LicenseKind.MIT), "https://jsoup.org"),
    ThirdPartyNotice("slf4j", R.string.about_third_party_slf4j, ThirdPartyLicense.Spdx(LicenseKind.MIT), "https://www.slf4j.org"),
    ThirdPartyNotice("desugar-jdk-libs", R.string.about_third_party_desugar, ThirdPartyLicense.Spdx(LicenseKind.GPL_2_CLASSPATH), "https://github.com/google/desugar_jdk_libs"),
    ThirdPartyNotice("bangumi-smileys", R.string.about_third_party_bangumi_smileys, ThirdPartyLicense.Notice(R.string.about_bangumi_smileys_license), "https://lain.bgm.tv/img/smiles/"),
    ThirdPartyNotice("platform-logos", R.string.about_third_party_platform_logos, ThirdPartyLicense.Notice(R.string.about_platform_logos_license), "https://simpleicons.org"),
    ThirdPartyNotice(
        "android-robot",
        R.string.about_third_party_android_robot,
        ThirdPartyLicense.Notice(R.string.about_android_robot_license),
        "https://developer.android.com/distribute/marketing-tools/brand-guidelines",
    ),
)

/**
 * 运行时从网上取用、不随包分发、但显示给用户的第三方数据（许可要求署名），是 About 与文档「运行时取用的第三方数据」表的唯一 catalog。
 */
internal val RuntimeDataNotices: List<ThirdPartyNotice> = listOf(
    ThirdPartyNotice("bangumi-data", R.string.about_third_party_bangumi_data, ThirdPartyLicense.Spdx(LicenseKind.CC_BY_4), "https://github.com/bangumi-data/bangumi-data"),
)

/** 许可的展示文字。SPDX 标识保持原样；没有开源许可的素材显示自己的版权声明。 */
internal fun ThirdPartyLicense.label(context: Context): String = when (this) {
    is ThirdPartyLicense.Spdx -> kind.label
    is ThirdPartyLicense.Notice -> context.getString(textRes)
}

/** 版权所有者的 GitHub 主页。 */
internal const val OwnerHomepageUrl = "https://github.com/TouhouGO"

/** 项目源码的公开地址，在所有者主页之下。 */
internal const val SourceRepositoryUrl = "$OwnerHomepageUrl/Chogumi"

/** 隐私说明：仓库根的 PRIVACY.md，随源码维护。 */
internal const val PrivacyNoticeUrl = "$SourceRepositoryUrl/blob/main/PRIVACY.md"

/**
 * 许可原文按 80 列硬换行、标题带缩进：显示前去掉每行首尾空白，把段内的单个换行并成空格，只保留空行分段，
 * 让文字按屏宽自己折行（放大字号、窄屏下行长才整齐）。
 */
internal fun unwrapParagraphs(text: String): String =
    text.lines().map(String::trim).fold(mutableListOf<MutableList<String>>(mutableListOf())) { paragraphs, line ->
        if (line.isEmpty()) {
            if (paragraphs.last().isNotEmpty()) paragraphs += mutableListOf<String>()
        } else {
            paragraphs.last() += line
        }
        paragraphs
    }.filter { it.isNotEmpty() }.joinToString("\n\n") { it.joinToString(" ") }
