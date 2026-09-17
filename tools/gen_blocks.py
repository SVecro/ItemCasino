"""Regenerates the block and pocket-item textures in the GUI's palette.

The point of this file is that the tables match their own screens: the same walnut frame, the same
gold pinstripe, the same green baize, so opening an Upgrader looks like sitting down at the thing
you just placed. Run from the project root:  python3 tools/gen_blocks.py
"""
import math
from PIL import Image

S = 16
BLOCK = "src/main/resources/assets/itemcasino/textures/block/"
ITEM = "src/main/resources/assets/itemcasino/textures/item/"

EDGE        = (20, 14, 10, 255)
WOOD_DEEP   = (39, 26, 17, 255)
WOOD        = (65, 46, 31, 255)
WOOD_LIGHT  = (110, 80, 56, 255)
GOLD        = (216, 179, 74, 255)
GOLD_DEEP   = (138, 106, 30, 255)
GOLD_HI     = (240, 218, 147, 255)
FELT        = (46, 129, 90, 255)
FELT_DEEP   = (14, 58, 38, 255)
FELT_MID    = (30, 94, 64, 255)
CREAM       = (242, 231, 206, 255)
CARD_EDGE   = (38, 30, 22, 255)
RED         = (176, 44, 44, 255)
PLUM        = (58, 47, 62, 255)
CLEAR       = (0, 0, 0, 0)


def new():
    return Image.new("RGBA", (S, S), CLEAR)


def rect(px, x0, y0, x1, y1, colour):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = colour


def ring(px, inset, colour):
    a, b = inset, S - 1 - inset
    for i in range(a, b + 1):
        px[i, a] = colour
        px[i, b] = colour
        px[a, i] = colour
        px[b, i] = colour


def felt_top(px):
    """The table surface: the panel border and baize of the screen, at one sixteenth the size."""
    rect(px, 0, 0, S - 1, S - 1, FELT)
    ring(px, 0, EDGE)
    ring(px, 1, WOOD_LIGHT)
    ring(px, 2, GOLD_DEEP)
    ring(px, 3, FELT_DEEP)
    ring(px, 4, FELT_MID)


def wheel(px, cx, cy, r):
    """A prize wheel small enough to read: gold sector, dark remainder, spokes, gold hub."""
    for y in range(S):
        for x in range(S):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            if d > r:
                continue
            if d > r - 0.9:
                px[x, y] = GOLD_DEEP
                continue
            if d < 1.2:
                px[x, y] = GOLD_HI
                continue
            angle = math.atan2(dy, dx) % (2 * math.pi)
            # Four quarters alternating, which at this size reads as a wheel where a single
            # narrow winning sector would just read as a smudge.
            quarter = int(angle / (math.pi / 2)) % 2
            px[x, y] = GOLD if quarter == 0 else PLUM
    top = int(round(cy - r)) - 1
    if top >= 0:
        px[int(cx), top] = CREAM
        px[int(cx) - 1, top] = CREAM


def coin(px, cx, cy, r):
    for y in range(S):
        for x in range(S):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if d > r:
                continue
            if d > r - 0.9:
                px[x, y] = GOLD_DEEP
            elif d > r - 2.0:
                px[x, y] = GOLD
            else:
                px[x, y] = GOLD_HI if (x + y) % 2 == 0 else GOLD
    px[int(cx) - 1, int(cy) - 1] = GOLD_DEEP
    px[int(cx), int(cy)] = GOLD_DEEP


def card(px, x, y, w, h, pip):
    rect(px, x, y, x + w - 1, y + h - 1, CARD_EDGE)
    rect(px, x + 1, y + 1, x + w - 2, y + h - 2, CREAM)
    px[x + w // 2, y + h // 2] = pip
    px[x + w // 2, y + h // 2 - 1] = pip


def wood_side(px):
    rect(px, 0, 0, S - 1, S - 1, WOOD)
    for x in range(S):
        px[x, 0] = WOOD_LIGHT
        px[x, S - 1] = EDGE
        px[x, S - 2] = WOOD_DEEP
    for x in (2, 6, 9, 13):
        for y in range(1, S - 2):
            if (x * 7 + y * 3) % 5:
                px[x, y] = WOOD_DEEP
    rect(px, 0, 1, S - 1, 1, GOLD_DEEP)
    rect(px, 0, 2, S - 1, 2, EDGE)
    rect(px, 0, 12, S - 1, 12, GOLD_DEEP)


def emblem_wheel(px):
    wheel(px, 8, 7.5, 3.4)


def emblem_coin(px):
    coin(px, 8, 7.5, 3.4)


def emblem_vault(px):
    """A locked door with a gold wheel on it: obviously where the money is."""
    rect(px, 4, 4, 11, 11, EDGE)
    rect(px, 5, 5, 10, 10, WOOD_LIGHT)
    coin(px, 8, 8, 2.6)
    for dx, dy in ((0, -4), (0, 3), (-4, 0), (3, 0)):
        px[8 + dx, 8 + dy] = GOLD


def emblem_reels(px):
    """Three lit windows side by side: the machine seen head-on, at one sixteenth scale."""
    for i, colour in enumerate((GOLD, CREAM, GOLD)):
        x = 4 + i * 3
        rect(px, x, 5, x + 1, 10, EDGE)
        rect(px, x, 6, x + 1, 9, colour)
    px[4, 4] = GOLD_DEEP
    px[10, 4] = GOLD_DEEP
    rect(px, 3, 11, 12, 11, GOLD_DEEP)


def emblem_mines(px):
    """A three by three patch of the board: turned tiles, hidden ones, and one mine found."""
    for row in range(3):
        for col in range(3):
            x, y = 4 + col * 3, 4 + row * 3
            if (row, col) == (1, 2):
                rect(px, x, y, x + 1, y + 1, RED)
                px[x, y] = EDGE
            elif (row + col) % 2 == 0:
                rect(px, x, y, x + 1, y + 1, FELT)
                px[x, y] = GOLD_HI
            else:
                rect(px, x, y, x + 1, y + 1, WOOD_LIGHT)
                px[x + 1, y + 1] = WOOD_DEEP


def die(px, x, y, size, pips):
    """A cream die with dark pips, a shade on its lower edge so it reads as a cube."""
    rect(px, x, y, x + size - 1, y + size - 1, CARD_EDGE)
    rect(px, x + 1, y + 1, x + size - 2, y + size - 2, CREAM)
    rect(px, x + 1, y + size - 2, x + size - 2, y + size - 2, GOLD_HI)
    for dx, dy in pips:
        px[x + dx, y + dy] = CARD_EDGE


def emblem_dice(px):
    """One die showing five: the shape everyone reads as dice at any size."""
    die(px, 4, 4, 8, ((2, 2), (5, 2), (2, 5), (5, 5), (3, 3), (4, 3), (3, 4), (4, 4)))


def emblem_duel(px):
    """Two coins facing off: one red chair, one blue, which is the whole game in one picture."""
    coin(px, 5, 7.5, 2.8)
    coin(px, 11, 7.5, 2.8)
    for y in range(4, 12):
        px[8, y] = EDGE


def emblem_cards(px):
    card(px, 4, 5, 4, 7, RED)
    card(px, 8, 4, 4, 7, CARD_EDGE)


def emblem_chip(px):
    """One casino chip seen from above, red notches on its gold rim: the cashier's sign."""
    for y in range(S):
        for x in range(S):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if d <= 4.6:
                px[x, y] = EDGE if d > 3.9 else (GOLD if d > 2.6 else (RED if d > 1.6 else GOLD_HI))
    for x, y in ((7, 4), (8, 4), (7, 11), (8, 11), (4, 7), (4, 8), (11, 7), (11, 8)):
        px[x, y] = CREAM


def emblem_teller(px):
    """The cashier's window seen from the front: a brass grille with a chip passing under it."""
    rect(px, 3, 4, 12, 10, EDGE)
    rect(px, 4, 5, 11, 9, FELT_DEEP)
    for x in (5, 7, 9, 11):
        for y in range(5, 10):
            px[x - 1 if x == 11 else x, y] = GOLD_DEEP
    rect(px, 6, 10, 9, 11, GOLD)
    px[6, 10] = GOLD_HI
    px[9, 11] = GOLD_DEEP


def chip_card():
    """A gold-striped card with a chip printed on it: the balance lives on the stack, not in the picture."""
    image = new()
    px = image.load()
    rect(px, 1, 3, 14, 12, CARD_EDGE)
    rect(px, 2, 4, 13, 11, CREAM)
    rect(px, 2, 5, 13, 6, GOLD)
    rect(px, 2, 7, 13, 7, GOLD_DEEP)
    rect(px, 3, 9, 6, 9, WOOD_LIGHT)
    rect(px, 3, 10, 5, 10, WOOD_LIGHT)
    rect(px, 10, 8, 12, 10, GOLD)
    px[11, 9] = RED
    px[10, 8] = GOLD_DEEP
    px[12, 10] = GOLD_DEEP
    for x, y in ((1, 3), (14, 3), (1, 12), (14, 12)):
        px[x, y] = CLEAR
    image.save(ITEM + "chip_card.png")


def block(name, top_motif, side_motif):
    top = new()
    felt_top(top.load())
    top_motif(top.load())
    top.save(BLOCK + name + "_top.png")

    side = new()
    wood_side(side.load())
    side_motif(side.load())
    side.save(BLOCK + name + "_side.png")


def bottom():
    image = new()
    px = image.load()
    rect(px, 0, 0, S - 1, S - 1, WOOD_DEEP)
    ring(px, 0, EDGE)
    for y in range(2, S - 2):
        for x in range(2, S - 2):
            if (x + y) % 4 == 0:
                px[x, y] = WOOD
    image.save(BLOCK + "casino_bottom.png")


def pocket(name, motif):
    """A little brass-cased device: the same table, folded up small enough to carry."""
    image = new()
    px = image.load()
    rect(px, 2, 1, 13, 14, EDGE)
    rect(px, 3, 2, 12, 13, WOOD_LIGHT)
    rect(px, 3, 3, 12, 12, WOOD)
    rect(px, 4, 3, 11, 12, GOLD_DEEP)
    rect(px, 4, 4, 11, 11, FELT_DEEP)
    rect(px, 5, 5, 10, 10, FELT)
    for x in range(4, 12):
        px[x, 13] = GOLD_DEEP
    px[3, 1] = CLEAR
    px[12, 1] = CLEAR
    px[3, 14] = CLEAR
    px[12, 14] = CLEAR
    motif(px)
    image.save(ITEM + name + ".png")


def main():
    block("upgrader", emblem_wheel, emblem_wheel)
    block("predict_the_dice", emblem_dice, emblem_dice)
    block("blackjack_table", emblem_cards, emblem_cards)
    block("coin_flip", emblem_duel, emblem_duel)
    block("slot_machine", emblem_reels, emblem_reels)
    block("vault", emblem_vault, emblem_vault)
    block("mine_field", emblem_mines, emblem_mines)
    block("cashier", emblem_chip, emblem_teller)
    bottom()

    pocket("pocket_upgrader", lambda px: wheel(px, 8, 8, 2.9))
    pocket("pocket_dice", lambda px: die(px, 5, 5, 6, ((1, 1), (2, 2), (3, 3))))
    pocket("pocket_blackjack", lambda px: (card(px, 4, 6, 4, 6, RED), card(px, 8, 4, 4, 6, CARD_EDGE)))
    chip_card()
    print("wrote 9 block textures and 4 item textures")


if __name__ == "__main__":
    main()
