"""Regenerates the playing-card atlas: 13 ranks across, one row per suit, card back in row 5.

Run from the project root:  python3 tools/gen_cards.py
"""
from PIL import Image

W, H = 24, 34
COLS, ROWS = 13, 5
ATLAS = (512, 256)

EDGE   = (38, 30, 22, 255)
FACE   = (247, 242, 230, 255)
SHADE  = (214, 205, 186, 255)
RED    = (176, 44, 44, 255)
BLACK  = (32, 28, 26, 255)
BACK_A = (122, 32, 38, 255)
BACK_B = (92, 22, 28, 255)
BACK_G = (201, 162, 39, 255)
CLEAR  = (0, 0, 0, 0)

GLYPHS = {
    "A": [".#.", "#.#", "###", "#.#", "#.#"],
    "2": ["###", "..#", "###", "#..", "###"],
    "3": ["###", "..#", "###", "..#", "###"],
    "4": ["#.#", "#.#", "###", "..#", "..#"],
    "5": ["###", "#..", "###", "..#", "###"],
    "6": ["###", "#..", "###", "#.#", "###"],
    "7": ["###", "..#", "..#", "..#", "..#"],
    "8": ["###", "#.#", "###", "#.#", "###"],
    "9": ["###", "#.#", "###", "..#", "###"],
    "0": ["###", "#.#", "#.#", "#.#", "###"],
    "1": [".#.", "##.", ".#.", ".#.", "###"],
    "J": ["..#", "..#", "..#", "#.#", "###"],
    "Q": ["###", "#.#", "#.#", "##.", ".##"],
    "K": ["#.#", "#.#", "##.", "#.#", "#.#"],
}

BIG = {
    0: ["....#....", "...###...", "..#####..", ".#######.", "#########",
        "#########", "#########", ".#######.", "...###...", "....#....", "...###..."],
    1: [".##...##.", "#########", "#########", "#########", "#########",
        ".#######.", "..#####..", "...###...", "....#....", ".........", "........."],
    2: ["....#....", "...###...", "..#####..", ".#######.", "#########",
        "#########", "#########", ".#######.", "..#####..", "...###...", "....#...."],
    3: ["...###...", "..#####..", "..#####..", "##..#..##", "#########",
        "#########", "##..#..##", "....#....", "...###...", "..#####..", "........."],
}

SMALL = {
    0: ["..#..", ".###.", "#####", ".#.#.", "..#.."],
    1: [".#.#.", "#####", "#####", ".###.", "..#.."],
    2: ["..#..", ".###.", "#####", ".###.", "..#.."],
    3: ["..#..", ".###.", "#####", "#####", "..#.."],
}

RANK_LABEL = "A23456789" + "10" + "JQK"
RANK_TEXT = ["A"] + [str(n) for n in range(2, 10)] + ["10", "J", "Q", "K"]


def stamp(px, art, ox, oy, colour, flip=False):
    rows = art[::-1] if flip else art
    for y, row in enumerate(rows):
        line = row[::-1] if flip else row
        for x, ch in enumerate(line):
            if ch == "#":
                px[ox + x, oy + y] = colour


def text(px, s, ox, oy, colour, flip=False):
    glyphs = [GLYPHS[c] for c in s]
    if flip:
        glyphs = glyphs[::-1]
    x = ox
    for art in glyphs:
        stamp(px, art, x, oy, colour, flip)
        x += 4


def blank_card(px, ox, oy):
    for y in range(H):
        for x in range(W):
            px[ox + x, oy + y] = FACE
    # rounded corners
    for (cx, cy) in [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)]:
        px[ox + cx, oy + cy] = CLEAR
    for x in range(W):
        px[ox + x, oy] = EDGE
        px[ox + x, oy + H - 1] = EDGE
    for y in range(H):
        px[ox, oy + y] = EDGE
        px[ox + W - 1, oy + y] = EDGE
    for (cx, cy) in [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)]:
        px[ox + cx, oy + cy] = CLEAR
    # a hint of thickness along the bottom and right
    for x in range(2, W - 2):
        px[ox + x, oy + H - 2] = SHADE
    for y in range(2, H - 2):
        px[ox + W - 2, oy + y] = SHADE


def main():
    image = Image.new("RGBA", ATLAS, CLEAR)
    px = image.load()

    for suit in range(4):
        colour = RED if suit in (1, 2) else BLACK
        for rank in range(COLS):
            ox, oy = rank * W, suit * H
            blank_card(px, ox, oy)
            label = RANK_TEXT[rank]

            # Rank top-left, the big pip in the middle, one small pip bottom-right. Anything more
            # than that is mush at twenty-four pixels wide, and the top-left corner is the part
            # that stays visible when the hand fans out and the cards overlap.
            text(px, label, ox + 3, oy + 4, colour)
            stamp(px, BIG[suit], ox + 8, oy + 14, colour)
            stamp(px, SMALL[suit], ox + W - 9, oy + H - 9, colour)

    # the back
    ox, oy = 0, 4 * H
    blank_card(px, ox, oy)
    for y in range(1, H - 1):
        for x in range(1, W - 1):
            px[ox + x, oy + y] = BACK_A if (x + y) % 2 == 0 else BACK_B
    for y in range(3, H - 3):
        for x in range(3, W - 3):
            if (x + y) % 6 == 0 or (x - y) % 6 == 0:
                px[ox + x, oy + y] = BACK_G
    for x in range(2, W - 2):
        px[ox + x, oy + 2] = BACK_G
        px[ox + x, oy + H - 3] = BACK_G
    for y in range(2, H - 2):
        px[ox + 2, oy + y] = BACK_G
        px[ox + W - 3, oy + y] = BACK_G
    for (cx, cy) in [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)]:
        px[ox + cx, oy + cy] = CLEAR

    image.save("src/main/resources/assets/itemcasino/textures/gui/cards.png")
    print("wrote cards.png", ATLAS)


if __name__ == "__main__":
    main()
