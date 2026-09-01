import struct, sys
def u1(x): return struct.pack(">B", x)
def u2(x): return struct.pack(">H", x)
def u4(x): return struct.pack(">I", x)
def utf8(s):
    b = s.encode("utf-8"); return u1(1) + u2(len(b)) + b

major = int(sys.argv[1]); name = sys.argv[2]
cp = [
    u1(7) + u2(2), utf8(name), u1(7) + u2(4), utf8("java/lang/Object"),
    utf8("main"), utf8("([Ljava/lang/String;)V"), utf8("Code"),
    u1(9) + u2(9) + u2(12), u1(7) + u2(10), utf8("java/lang/System"),
    utf8("out"), u1(12) + u2(11) + u2(13), utf8("Ljava/io/PrintStream;"),
    u1(8) + u2(15), utf8("major %d: a BRANCH verified with no StackMapTable" % major),
    u1(10) + u2(17) + u2(20), u1(7) + u2(18), utf8("java/io/PrintStream"),
    utf8("println"), u1(12) + u2(19) + u2(21), utf8("(Ljava/lang/String;)V"),
]
# iconst_1; ifeq +6 -> L; getstatic; ldc; invokevirtual; L: return
#  0: 04            iconst_1
#  1: 99 00 09      ifeq -> 10   (branch target 10 == the `return`)
#  4: b2 00 08      getstatic
#  7: 12 0e         ldc            (7,8)
#  9: b6 00 10      invokevirtual  -> wait, that lands at 9..11, target must be 12
# recompute: 0 iconst_1(1) | 1 ifeq(3) | 4 getstatic(3) | 7 ldc(2) | 9 invokevirtual(3) | 12 return
code = (b"\x04" + b"\x99" + u2(11) + b"\xb2" + u2(8) + b"\x12" + u1(14)
        + b"\xb6" + u2(16) + b"\xb1")
assert len(code) == 13, len(code)
code_attr = u2(2) + u2(1) + u4(len(code)) + code + u2(0) + u2(0)
method = u2(0x0009) + u2(5) + u2(6) + u2(1) + u2(7) + u4(len(code_attr)) + code_attr
cf = (u4(0xCAFEBABE) + u2(0) + u2(major) + u2(len(cp) + 1) + b"".join(cp)
      + u2(0x0021) + u2(1) + u2(3) + u2(0) + u2(0) + u2(1) + method + u2(0))
open(name + ".class", "wb").write(cf)
print("wrote %s.class major=%d" % (name, major))
