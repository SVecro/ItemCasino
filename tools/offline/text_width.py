# Estimated pixel widths in the default Minecraft font, for checking that labels fit.
import json,sys
W1=set("!',.:;i|"); W2=set("`l"); W3=set('"()*I[]t{}'); W4=set("<>fk"); W6=set("@~")
ACC={'î':3,'ï':3,'Î':3}
def cw(c):
    if c==' ': return 4
    if c in ACC: return ACC[c]+1
    if c in W1: return 2
    if c in W2: return 3
    if c in W3: return 4
    if c in W4: return 5
    if c in W6: return 7
    if c=='—': return 9
    if c=='…': return 8
    if c in '«»': return 6
    return 6
def width(s): return sum(cw(c) for c in s)


if __name__ == '__main__':
    # Estimated pixel width of each argument in Minecraft's default font (about ±10 %).
    #   python3 tools/offline/text_width.py "Chip card needed" "Set your bet"
    for text in sys.argv[1:]:
        print(width(text), text)
