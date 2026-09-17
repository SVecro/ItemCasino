"""Draws the goblin's skin on the standard 64x64 humanoid layout.

The rig is vanilla's, so the texture has to land on vanilla's UVs: head at (0,0), the hat layer at
(32,0), body at (16,16), right arm at (40,16), left arm at (32,48), right leg at (0,16), left leg
at (16,48). Each of those is a cross of six faces, and what matters for a flat-shaded character is
only that the right rectangles are the right colour. Run from the project root.
"""
from PIL import Image

SKIN      = (106, 152, 72, 255)
SKIN_DARK = (78, 116, 52, 255)
COAT      = (122, 32, 38, 255)
COAT_DARK = (92, 22, 28, 255)
GOLD      = (216, 179, 74, 255)
GOLD_DARK = (138, 106, 30, 255)
BOOT      = (58, 40, 26, 255)
BOOT_DARK = (40, 27, 17, 255)
EYE       = (240, 232, 200, 255)
PUPIL     = (24, 20, 16, 255)
MOUTH     = (52, 28, 28, 255)
CLEAR     = (0, 0, 0, 0)

image = Image.new("RGBA", (64, 64), CLEAR)
px = image.load()


def box(x, y, w, h, colour):
    for j in range(y, y + h):
        for i in range(x, x + w):
            px[i, j] = colour


def limb(ox, oy, w, d, h, light, dark):
    """One limb cross: top and bottom, then the four sides in a row underneath."""
    box(ox + d, oy, w, d, light)                 # top
    box(ox + d + w, oy, w, d, dark)              # bottom
    box(ox, oy + d, d, h, dark)                  # right side
    box(ox + d, oy + d, w, h, light)             # front
    box(ox + d + w, oy + d, d, h, dark)          # left side
    box(ox + d + w + d, oy + d, w, h, dark)      # back


# --- head, and a face on the front of it -----------------------------------
limb(0, 0, 8, 8, 8, SKIN, SKIN_DARK)
box(9, 12, 2, 2, EYE)
box(13, 12, 2, 2, EYE)
px[10, 13] = PUPIL
px[14, 13] = PUPIL
box(11, 15, 2, 1, SKIN_DARK)      # a long nose
box(10, 16, 4, 1, MOUTH)          # and a wide grin
# ears, poking out of the sides
box(0, 10, 2, 3, SKIN_DARK)
box(22, 10, 2, 3, SKIN_DARK)

# --- the hat layer: a squat gold-banded topper ------------------------------
limb(32, 0, 8, 8, 8, COAT_DARK, COAT_DARK)
box(40, 9, 8, 2, GOLD)            # band across the front
box(40, 8, 8, 1, GOLD_DARK)

# --- body: a red coat with gold buttons -------------------------------------
limb(16, 16, 8, 4, 12, COAT, COAT_DARK)
px[23, 22] = GOLD
px[23, 25] = GOLD
px[23, 28] = GOLD
box(20, 20, 8, 1, GOLD_DARK)      # collar

# --- arms and legs ----------------------------------------------------------
limb(40, 16, 4, 4, 12, COAT, COAT_DARK)
box(44, 26, 4, 6, SKIN)           # bare green forearm on the front face
limb(32, 48, 4, 4, 12, COAT, COAT_DARK)
box(36, 58, 4, 6, SKIN)
limb(0, 16, 4, 4, 12, BOOT, BOOT_DARK)
limb(16, 48, 4, 4, 12, BOOT, BOOT_DARK)

image.save("src/main/resources/assets/itemcasino/textures/entity/gambler_goblin.png")
print("wrote gambler_goblin.png 64x64")
