#!/usr/bin/env python3
"""Build the bundled card database (app/src/main/assets/carddb.bin).

Source: CardDefs.xml from the `hearthstone_data` package on PyPI
(the same data HearthstoneJSON is generated from).

Output: a HearthstoneJSON-compatible JSON array, with `name` holding a
{locale: name} object for the locales listed in LOCALES.

Usage:
    pip install hearthstone hearthstone_data
    python3 tools/build_card_db.py [path/to/CardDefs.xml]
"""
import gzip
import json
import os
import sys
import xml.etree.ElementTree as ET

from hearthstone import enums

LOCALES = ("enUS", "koKR")
SKIPPED_TYPES = {
    enums.CardType.INVALID, enums.CardType.GAME, enums.CardType.PLAYER,
    enums.CardType.ENCHANTMENT, enums.CardType.ITEM, enums.CardType.TOKEN,
    enums.CardType.BLANK, enums.CardType.GAME_MODE_BUTTON,
    enums.CardType.MOVE_MINION_HOVER_TARGET, enums.CardType.LETTUCE_ABILITY,
}
SKIPPED_SETS = {enums.CardSet.LETTUCE, enums.CardSet.MERCENARIES_DEV}

TAG_CARDNAME = enums.GameTag.CARDNAME.value
TAG_CARDTEXT = enums.GameTag.CARDTEXT.value
TAG_COST = enums.GameTag.COST.value
TAG_ATK = enums.GameTag.ATK.value
TAG_HEALTH = enums.GameTag.HEALTH.value
TAG_CARDTYPE = enums.GameTag.CARDTYPE.value
TAG_CLASS = enums.GameTag.CLASS.value
TAG_RARITY = enums.GameTag.RARITY.value
TAG_SET = enums.GameTag.CARD_SET.value
TAG_COLLECTIBLE = enums.GameTag.COLLECTIBLE.value
TAG_TECH_LEVEL = enums.GameTag.TECH_LEVEL.value


def enum_name(enum_cls, value):
    try:
        return enum_cls(value).name
    except ValueError:
        return None


def default_carddefs_path():
    import hearthstone_data
    return os.path.join(os.path.dirname(hearthstone_data.__file__), "CardDefs.xml")


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else default_carddefs_path()
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root_dir, "app", "src", "main", "assets", "carddb.bin")

    cards = []
    build = None
    for event, elem in ET.iterparse(src, events=("start", "end")):
        if event == "start":
            if elem.tag == "CardDefs":
                build = elem.get("build")
            continue
        if elem.tag != "Entity":
            continue
        tags = {}
        names = {}
        texts = {}
        for tag in elem.findall("Tag"):
            enum_id = int(tag.get("enumID"))
            if enum_id in (TAG_CARDNAME, TAG_CARDTEXT):
                target = names if enum_id == TAG_CARDNAME else texts
                for loc in LOCALES:
                    node = tag.find(loc)
                    if node is not None and node.text:
                        target[loc] = node.text
            elif tag.get("type") in ("Int", "Bool"):
                tags[enum_id] = int(tag.get("value", "0"))
        card_type = tags.get(TAG_CARDTYPE, 0)
        try:
            card_type_enum = enums.CardType(card_type)
        except ValueError:
            card_type_enum = enums.CardType.INVALID
        card_set = tags.get(TAG_SET, 0)
        try:
            card_set_enum = enums.CardSet(card_set)
        except ValueError:
            card_set_enum = None
        if card_type_enum in SKIPPED_TYPES or card_set_enum in SKIPPED_SETS or not names:
            elem.clear()
            continue
        card = {
            "id": elem.get("CardID"),
            "dbfId": int(elem.get("ID")),
            "name": names,
            "type": card_type_enum.name,
            "cardClass": enum_name(enums.CardClass, tags.get(TAG_CLASS, 0)) or "NEUTRAL",
        }
        if TAG_COST in tags:
            card["cost"] = tags[TAG_COST]
        if card_type_enum in (enums.CardType.MINION, enums.CardType.WEAPON, enums.CardType.HERO):
            card["attack"] = tags.get(TAG_ATK, 0)
            card["health"] = tags.get(TAG_HEALTH, 0)
        rarity = enum_name(enums.Rarity, tags.get(TAG_RARITY, 0))
        if rarity and rarity != "INVALID":
            card["rarity"] = rarity
        if card_set_enum is not None:
            card["set"] = card_set_enum.name
        if tags.get(TAG_COLLECTIBLE):
            card["collectible"] = True
            # Card text helps recognize draft offers by OCR; only needed for collectible cards.
            if texts:
                card["text"] = texts
        if tags.get(TAG_TECH_LEVEL):
            card["techLevel"] = tags[TAG_TECH_LEVEL]
        cards.append(card)
        elem.clear()

    cards.sort(key=lambda c: c["dbfId"])
    payload = json.dumps(cards, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with gzip.GzipFile(out, "wb", mtime=0) as f:
        f.write(payload)
    print(f"build={build} cards={len(cards)} "
          f"collectible={sum(1 for c in cards if c.get('collectible'))} "
          f"raw={len(payload)} gz={os.path.getsize(out)} -> {out}")


if __name__ == "__main__":
    main()
