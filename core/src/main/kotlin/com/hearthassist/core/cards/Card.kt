package com.hearthassist.core.cards

data class Card(
    val id: String,
    val dbfId: Int,
    val names: Map<String, String>,
    val type: String,
    val cardClass: String,
    val cost: Int?,
    val attack: Int?,
    val health: Int?,
    val rarity: String?,
    val set: String?,
    val collectible: Boolean,
) {
    fun name(locale: String): String =
        names[locale] ?: names[DEFAULT_LOCALE] ?: names.values.firstOrNull() ?: id

    val heroClass: HeroClass? get() = HeroClass.fromCardClass(cardClass)

    /** Cards that can be offered in an Arena draft (hero skins and classic duplicates excluded). */
    val isDraftable: Boolean
        get() = collectible && set !in NON_DRAFT_SETS &&
            (type == "MINION" || type == "SPELL" || type == "WEAPON" || type == "LOCATION" || type == "HERO")

    companion object {
        const val DEFAULT_LOCALE = "enUS"
        private val NON_DRAFT_SETS = setOf("HERO_SKINS", "VANILLA", "CORE_HIDDEN", "PLACEHOLDER_202204")
    }
}

enum class HeroClass(val cardClass: String, val koName: String, val enName: String) {
    DEATHKNIGHT("DEATHKNIGHT", "죽음의 기사", "Death Knight"),
    DEMONHUNTER("DEMONHUNTER", "악마사냥꾼", "Demon Hunter"),
    DRUID("DRUID", "드루이드", "Druid"),
    HUNTER("HUNTER", "사냥꾼", "Hunter"),
    MAGE("MAGE", "마법사", "Mage"),
    PALADIN("PALADIN", "성기사", "Paladin"),
    PRIEST("PRIEST", "사제", "Priest"),
    ROGUE("ROGUE", "도적", "Rogue"),
    SHAMAN("SHAMAN", "주술사", "Shaman"),
    WARLOCK("WARLOCK", "흑마법사", "Warlock"),
    WARRIOR("WARRIOR", "전사", "Warrior");

    /** Context key used by the Firestone arena stats endpoints. */
    val statsKey: String get() = cardClass.lowercase()

    fun displayName(locale: String): String = if (locale == "koKR") koName else enName

    companion object {
        fun fromCardClass(value: String?): HeroClass? =
            value?.let { v -> entries.firstOrNull { it.cardClass.equals(v, ignoreCase = true) } }
    }
}
