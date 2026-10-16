/*
 * M5 commit 0 (docs/design/m5-setup-settings.md S15, §3.9.6, §3.10.3): PBKDF2-HMAC-SHA256 microbenchmark that set
 * PIN_KDF_ITERATIONS and BACKUP_KDF_ITERATIONS. Variants: (a) SecretKeyFactory "PBKDF2WithHmacSHA256" (absent on API 25),
 * (b) the design's RFC 8018 loop over Mac("HmacSHA256"), (c) a pure-Java HMAC-SHA256 with precomputed pads. Checks the
 * RFC 7914 §11 vectors first. Not part of the build. Re-run on hardware in M6:
 *   javac --release 8 -d /tmp/kdf tools/kdfbench/KdfBench.java
 *   $ANDROID_HOME/build-tools/<ver>/d8 --output /tmp/kdf /tmp/kdf/KdfBench*.class
 *   adb push /tmp/kdf/classes.dex /data/local/tmp/kdf.dex
 *   adb shell CLASSPATH=/data/local/tmp/kdf.dex app_process /system/bin KdfBench
 * (app_process runs on ART JIT without the app's profile; numbers are a proxy.)
 */
import javax.crypto.*;
import javax.crypto.spec.*;
import java.util.*;

public class KdfBench {
  static byte[] pbkdf2(byte[] pw, byte[] salt, int c, int dkLen) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(pw, "HmacSHA256"));
    int hLen = 32; int blocks = (dkLen + hLen - 1) / hLen;
    byte[] out = new byte[dkLen]; byte[] u = new byte[hLen]; byte[] t = new byte[hLen];
    for (int i = 1; i <= blocks; i++) {
      mac.update(salt);
      mac.update(new byte[]{(byte)(i>>>24),(byte)(i>>>16),(byte)(i>>>8),(byte)i});
      mac.doFinal(u, 0);
      System.arraycopy(u, 0, t, 0, hLen);
      for (int j = 1; j < c; j++) {
        mac.update(u, 0, hLen);
        mac.doFinal(u, 0);
        for (int k = 0; k < hLen; k++) t[k] ^= u[k];
      }
      System.arraycopy(t, 0, out, (i-1)*hLen, Math.min(hLen, dkLen-(i-1)*hLen));
    }
    return out;
  }

  // (c) pure-Java HMAC-SHA256 with precomputed ipad/opad states; 2 compressions per iteration.
  static final int[] K = {0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
  static final int[] IV = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
  static void compress(int[] h, int[] w) { // w[0..15] filled, w length 64
    for (int t=16;t<64;t++){int x=w[t-15],y=w[t-2];
      w[t]=w[t-16]+(Integer.rotateRight(x,7)^Integer.rotateRight(x,18)^(x>>>3))+w[t-7]+(Integer.rotateRight(y,17)^Integer.rotateRight(y,19)^(y>>>10));}
    int a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],hh=h[7];
    for (int t=0;t<64;t++){
      int t1=hh+(Integer.rotateRight(e,6)^Integer.rotateRight(e,11)^Integer.rotateRight(e,25))+((e&f)^(~e&g))+K[t]+w[t];
      int t2=(Integer.rotateRight(a,2)^Integer.rotateRight(a,13)^Integer.rotateRight(a,22))+((a&b)^(a&c)^(b&c));
      hh=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;}
    h[0]+=a;h[1]+=b;h[2]+=c;h[3]+=d;h[4]+=e;h[5]+=f;h[6]+=g;h[7]+=hh;
  }
  static void padState(byte[] key, int pad, int[] out) throws Exception {
    byte[] k = key.length > 64 ? java.security.MessageDigest.getInstance("SHA-256").digest(key) : key;
    int[] w = new int[64];
    for (int i=0;i<16;i++){int v=0;for(int j=0;j<4;j++){int idx=i*4+j;v=(v<<8)|(((idx<k.length?k[idx]:0)^pad)&0xff);}w[i]=v;}
    System.arraycopy(IV,0,out,0,8); compress(out,w);
  }
  static byte[] pbkdf2Pure(byte[] pw, byte[] salt, int c, int dkLen) throws Exception {
    int[] ist=new int[8], ost=new int[8]; padState(pw,0x36,ist); padState(pw,0x5c,ost);
    Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(pw, "HmacSHA256"));
    int blocks=(dkLen+31)/32; byte[] out=new byte[dkLen];
    int[] u=new int[8], t=new int[8], h=new int[8], w=new int[64];
    for (int i=1;i<=blocks;i++){
      mac.update(salt); mac.update(new byte[]{(byte)(i>>>24),(byte)(i>>>16),(byte)(i>>>8),(byte)i});
      byte[] u1=mac.doFinal();
      for(int k=0;k<8;k++){u[k]=((u1[4*k]&0xff)<<24)|((u1[4*k+1]&0xff)<<16)|((u1[4*k+2]&0xff)<<8)|(u1[4*k+3]&0xff);t[k]=u[k];}
      for (int j=1;j<c;j++){
        // inner: H(ipad-state, u || pad) ; message len = 64+32 bytes = 768 bits
        System.arraycopy(ist,0,h,0,8); System.arraycopy(u,0,w,0,8); w[8]=0x80000000; for(int k=9;k<15;k++)w[k]=0; w[15]=768; compress(h,w);
        System.arraycopy(ost,0,u,0,8); System.arraycopy(h,0,w,0,8); w[8]=0x80000000; for(int k=9;k<15;k++)w[k]=0; w[15]=768; compress(u,w);
        for(int k=0;k<8;k++)t[k]^=u[k];
      }
      for(int k=0;k<8;k++){int o=(i-1)*32+4*k; for(int b=0;b<4;b++) if(o+b<dkLen) out[o+b]=(byte)(t[k]>>>(24-8*b));}
    }
    return out;
  }
  static byte[] factory(char[] pw, byte[] salt, int c, int dkLen) throws Exception {
    SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    return f.generateSecret(new PBEKeySpec(pw, salt, c, dkLen*8)).getEncoded();
  }
  static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
  static void check(String name, byte[] got, String exp){System.out.println(name+": "+(hex(got).equals(exp)?"PASS":"FAIL "+hex(got)));}
  interface Fn { void run(int c) throws Exception; }
  static void bench(String label, Fn fn, int[] counts) throws Exception {
    for (int c : counts) {
      for (int w=0;w<3;w++) fn.run(c);
      double[] ms = new double[5];
      for (int r=0;r<5;r++){long t0=System.nanoTime(); fn.run(c); ms[r]=(System.nanoTime()-t0)/1e6;}
      double[] s = ms.clone(); Arrays.sort(s);
      System.out.printf("%s c=%d median=%.1fms max=%.1fms%n", label, c, s[2], s[4]);
    }
  }
  public static void main(String[] a) throws Exception {
    String v1="55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783";
    String v2="4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d";
    check("loop vec1", pbkdf2("passwd".getBytes("UTF-8"), "salt".getBytes("UTF-8"), 1, 64), v1);
    check("loop vec2", pbkdf2("Password".getBytes("UTF-8"), "NaCl".getBytes("UTF-8"), 80000, 64), v2);
    check("pure vec1", pbkdf2Pure("passwd".getBytes("UTF-8"), "salt".getBytes("UTF-8"), 1, 64), v1);
    check("pure vec2", pbkdf2Pure("Password".getBytes("UTF-8"), "NaCl".getBytes("UTF-8"), 80000, 64), v2);
    boolean fOk = true;
    try { check("factory vec1", factory("passwd".toCharArray(), "salt".getBytes("UTF-8"), 1, 64), v1);
          check("factory vec2", factory("Password".toCharArray(), "NaCl".getBytes("UTF-8"), 80000, 64), v2);
          System.out.println("factory provider: "+SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").getProvider());
    } catch (Exception e) { fOk=false; System.out.println("factory unavailable: "+e); }
    System.out.println("Mac provider: "+Mac.getInstance("HmacSHA256").getProvider());
    int[] counts = {10000,20000,50000,100000,200000,310000,400000,600000};
    final byte[] salt = new byte[16]; new Random(1).nextBytes(salt);
    final byte[] pw = "1234".getBytes("UTF-8");
    bench("loop(b)", c -> pbkdf2(pw, salt, c, 32), counts);
    bench("pure(c)", c -> pbkdf2Pure(pw, salt, c, 32), counts);
    if (fOk) bench("factory(a)", c -> factory("1234".toCharArray(), salt, c, 32), counts);
  }
}
