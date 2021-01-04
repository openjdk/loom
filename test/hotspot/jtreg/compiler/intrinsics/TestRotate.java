/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/**
 * @test
 * @bug 8248830 8256823
 * @summary Support for scalar rotates ([Integer/Long].rotate[Left/Right]).
 * @library /test/lib
 * @requires vm.compiler2.enabled
 *
 * @run main/othervm/timeout=600 -XX:-TieredCompilation -XX:CompileThreshold=1000 -Xbatch
 *      compiler.intrinsics.TestRotate
 *
 */

package compiler.intrinsics;

import java.util.Arrays;
import java.util.Random;
import jdk.test.lib.Utils;

public class TestRotate {
    static int ITERS = 500000;
    static int SIZE  = 32;
    static Random rand;

    static final int [] ref_rol_int = {1073741824,2147483647,-1847483168,-762700135,617181014,1499775590,770793873,-921711375,1843194553,618929189,543569581,-1524383055,-1358287055,-2015951670,1688073778,687346128,2069534693,-649937276,-1986172760,-1935023546,1291562794,-1493576900,1682263699,807071113,888440312,1299098553,1799312476,745578744,762073952,-1048231621,479680827,403988906};

    static final int [] ref_ror_int = {1,2147483647,-1847483168,1244166759,1284961634,1704135065,770793873,-53535600,-1217156379,118049081,1667944966,1766387884,-960747332,849475009,2106366247,-532201309,-111225179,-1590275921,1733962274,-1851577736,1055640211,1872573386,356142481,1649149627,1025605133,1537928787,1799312476,-131305312,190518488,82773525,1321674198,-2126112095};

    static final long [] ref_rol_long = {4611686018427387904L,9223372036854775807L,-3965526468698771170L,-4285866096636113521L,7635506276746300070L,5413117018148508287L,1868037460083876000L,-3244573585138770353L,7136025216317516898L,-4913621043675642569L,-6391133452542036978L,3902621950534797292L,-4632945906580257763L,4947809816008258399L,5235987658397734862L,2619582334080606650L,1815014778597694835L,-2451797983190531776L,-12499474356882106L,-8308822678069541478L,8441313153103433409L,3994820770127321462L,3403550366464210270L,-5787882067214947834L,-3689654055874130041L,-589861354719079036L,6641098980367723810L,763129181839551415L,4389436227302949880L,-8023110070632385182L,-8486732357167672789L,7236425339463197932L};

    static final long [] ref_ror_long = {1L,9223372036854775807L,-3965526468698771170L,1303279687165097535L,-6959108088026060186L,681250795361731838L,3705372465420868140L,-8117671657526198993L,-335604442896202624L,-3176041913244586253L,-2152781018329716108L,975655487633699323L,8574521504761035792L,-5473888690750018935L,1581768605333334728L,7674419410656225425L,6685114322387540375L,5780227587575757360L,-799966358840454721L,8284086884492323912L,5288463661042741341L,912426852973757747L,-11970671133582816L,-344117270115783853L,-2106591766031512621L,-857638554601955011L,6641098980367723810L,-5257223391402178581L,1097359056825737470L,-4640791861453503840L,8696676574724001348L,-6526192196514797544L};

    static final int [] ref_int_rol_shift_1 = {1,-2,600000961,1244166759,642480817,161047955,-1021158430,-2014938908,-1427785869,-467917532,857429648,-1524383055,1807911884,1884072061,845454838,1040479242,1471523198,1301556200,-1713993080,-1641781046,-809831772,-1713031218,-1529278125,-1361538303,1602441032,-689259628,-696342344,93197343,762073952,352644870,1972613577,223396003};

    static final int [] ref_int_rol_shift_127 = {1073741824 ,-1073741825 ,1223742064 ,-762700135 ,1234362028 ,-1033479836 ,-1329031432 ,570007097 ,-356946468 ,956762441 ,214357412 ,1766387884 ,451977971 ,1544759839 ,-1936119939 ,-1887363838 ,-1779602849 ,325389050 ,645243554 ,-1484187086 ,871283881 ,-1501999629 ,-382319532 ,1807099072 ,400610258 ,901426917 ,899656238 ,-1050442489 ,190518488 ,-2059322431 ,1566895218 ,-1017892824};

    static final int [] ref_int_rol_shift_128 = {-2147483648 ,2147483647 ,-1847483168 ,-1525400269 ,-1826243240 ,-2066959671 ,1636904433 ,1140014194 ,-713892935 ,1913524882 ,428714824 ,-762191528 ,903955942 ,-1205447618 ,422727419 ,520239621 ,735761599 ,650778100 ,1290487108 ,1326593125 ,1742567762 ,1290968039 ,-764639063 ,-680769152 ,801220516 ,1802853834 ,1799312476 ,-2100884977 ,381036976 ,176322435 ,-1161176860 ,-2035785647};

    static final int [] ref_int_rol_shift_M128 = {-2147483648 ,2147483647 ,-1847483168 ,-1525400269 ,-1826243240 ,-2066959671 ,1636904433 ,1140014194 ,-713892935 ,1913524882 ,428714824 ,-762191528 ,903955942 ,-1205447618 ,422727419 ,520239621 ,735761599 ,650778100 ,1290487108 ,1326593125 ,1742567762 ,1290968039 ,-764639063 ,-680769152 ,801220516 ,1802853834 ,1799312476 ,-2100884977 ,381036976 ,176322435 ,-1161176860 ,-2035785647};

    static final int [] ref_int_rol_shift_M129 = {1073741824 ,-1073741825 ,1223742064 ,-762700135 ,1234362028 ,-1033479836 ,-1329031432 ,570007097 ,-356946468 ,956762441 ,214357412 ,1766387884 ,451977971 ,1544759839 ,-1936119939 ,-1887363838 ,-1779602849 ,325389050 ,645243554 ,-1484187086 ,871283881 ,-1501999629 ,-382319532 ,1807099072 ,400610258 ,901426917 ,899656238 ,-1050442489 ,190518488 ,-2059322431 ,1566895218 ,-1017892824};

    static final int [] ref_int_ror_shift_1 = {1073741824,-1073741825,1223742064,-762700135,1234362028,-1033479836,-1329031432,570007097,-356946468,956762441,214357412,1766387884,451977971,1544759839,-1936119939,-1887363838,-1779602849,325389050,645243554,-1484187086,871283881,-1501999629,-382319532,1807099072,400610258,901426917,899656238,-1050442489,190518488,-2059322431,1566895218,-1017892824};

    static final int [] ref_int_ror_shift_127 = {1 ,-2 ,600000961 ,1244166759 ,642480817 ,161047955 ,-1021158430 ,-2014938908 ,-1427785869 ,-467917532 ,857429648 ,-1524383055 ,1807911884 ,1884072061 ,845454838 ,1040479242 ,1471523198 ,1301556200 ,-1713993080 ,-1641781046 ,-809831772 ,-1713031218 ,-1529278125 ,-1361538303 ,1602441032 ,-689259628 ,-696342344 ,93197343 ,762073952 ,352644870 ,1972613577 ,223396003};

    static final int [] ref_int_ror_shift_128 = {-2147483648 ,2147483647 ,-1847483168 ,-1525400269 ,-1826243240 ,-2066959671 ,1636904433 ,1140014194 ,-713892935 ,1913524882 ,428714824 ,-762191528 ,903955942 ,-1205447618 ,422727419 ,520239621 ,735761599 ,650778100 ,1290487108 ,1326593125 ,1742567762 ,1290968039 ,-764639063 ,-680769152 ,801220516 ,1802853834 ,1799312476 ,-2100884977 ,381036976 ,176322435 ,-1161176860 ,-2035785647};

    static final int [] ref_int_ror_shift_M128 = {-2147483648 ,2147483647 ,-1847483168 ,-1525400269 ,-1826243240 ,-2066959671 ,1636904433 ,1140014194 ,-713892935 ,1913524882 ,428714824 ,-762191528 ,903955942 ,-1205447618 ,422727419 ,520239621 ,735761599 ,650778100 ,1290487108 ,1326593125 ,1742567762 ,1290968039 ,-764639063 ,-680769152 ,801220516 ,1802853834 ,1799312476 ,-2100884977 ,381036976 ,176322435 ,-1161176860 ,-2035785647};

    static final int [] ref_int_ror_shift_M129 = {1 ,-2 ,600000961 ,1244166759 ,642480817 ,161047955 ,-1021158430 ,-2014938908 ,-1427785869 ,-467917532 ,857429648 ,-1524383055 ,1807911884 ,1884072061 ,845454838 ,1040479242 ,1471523198 ,1301556200 ,-1713993080 ,-1641781046 ,-809831772 ,-1713031218 ,-1529278125 ,-1361538303 ,1602441032 ,-689259628 ,-696342344 ,93197343 ,762073952 ,352644870 ,1972613577 ,223396003};


    static final long [] ref_long_rol_shift_1 = {
1L,-2L,-7931052937397542339L,1303279687165097535L,5608201114852140040L,359735415298403453L,4701815018953926360L,8969694797557082089L,324535527229983777L,-3508390168714987589L,-8153196119534272952L,-5889362595358906884L,8065135560209711367L,-3515635332702993867L,-3582426625105780649L,1632572717772861065L,3572202937482896855L,-7534931108784269461L,8161459789691976885L,5213383633793760703L,5933801933688073239L,-6730200469375698045L,6308363257973444605L,-3098812595652498694L,-541332749731694416L,9008962398204287055L,6200852250644175020L,5992317991244719550L,1486051504252676350L,-6863599526811956670L,5846438278934178867L,2838151117945983671L};

    static final long [] ref_long_rol_shift_127 = {4611686018427387904L,-4611686018427387905L,7240608802505390223L,-4285866096636113521L,1402050278713035010L,4701619872251988767L,1175453754738481590L,6854109717816658426L,4692819900234883848L,-877097542178746898L,2573386988543819666L,3139345369587661183L,-2595402128374960063L,8344463203679027341L,-895606656276445163L,5019829197870603170L,-3718635284056663691L,-1883732777196067366L,6652050965850382125L,-3308340109978947729L,-3128235535005369595L,-1682550117343924512L,6188776832920749055L,-5386389167340512578L,4476352830994464300L,-2359445418876316141L,1550213062661043755L,-7725292539043595921L,-8851859160791606721L,-6327585900130377072L,-3150076448693843188L,-3902148238940891987L};

    static final long [] ref_long_rol_shift_128 = {-9223372036854775808L,9223372036854775807L,-3965526468698771170L,-8571732193272227041L,2804100557426070020L,-9043504329205574082L,2350907509476963180L,-4738524638076234764L,-9061104273239783920L,-1754195084357493795L,5146773977087639332L,6278690739175322366L,-5190804256749920125L,-1757817666351496934L,-1791213312552890325L,-8407085677968345276L,-7437270568113327381L,-3767465554392134731L,-5142642142008787366L,-6616680219957895457L,-6256471070010739189L,-3365100234687849023L,-6069190407868053506L,7673965739028526461L,8952705661988928600L,-4718890837752632281L,3100426125322087510L,2996158995622359775L,743025752126338175L,5791572273448797473L,-6300152897387686375L,-7804296477881783973L};

    static final long [] ref_long_rol_shift_M128 = {-9223372036854775808L,9223372036854775807L,-3965526468698771170L,-8571732193272227041L,2804100557426070020L,-9043504329205574082L,2350907509476963180L,-4738524638076234764L,-9061104273239783920L,-1754195084357493795L,5146773977087639332L,6278690739175322366L,-5190804256749920125L,-1757817666351496934L,-1791213312552890325L,-8407085677968345276L,-7437270568113327381L,-3767465554392134731L,-5142642142008787366L,-6616680219957895457L,-6256471070010739189L,-3365100234687849023L,-6069190407868053506L,7673965739028526461L,8952705661988928600L,-4718890837752632281L,3100426125322087510L,2996158995622359775L,743025752126338175L,5791572273448797473L,-6300152897387686375L,-7804296477881783973L};


    static final long [] ref_long_rol_shift_M129 = {4611686018427387904L,-4611686018427387905L,7240608802505390223L,-4285866096636113521L,1402050278713035010L,4701619872251988767L,1175453754738481590L,6854109717816658426L,4692819900234883848L,-877097542178746898L,2573386988543819666L,3139345369587661183L,-2595402128374960063L,8344463203679027341L,-895606656276445163L,5019829197870603170L,-3718635284056663691L,-1883732777196067366L,6652050965850382125L,-3308340109978947729L,-3128235535005369595L,-1682550117343924512L,6188776832920749055L,-5386389167340512578L,4476352830994464300L,-2359445418876316141L,1550213062661043755L,-7725292539043595921L,-8851859160791606721L,-6327585900130377072L,-3150076448693843188L,-3902148238940891987L};

    static final long [] ref_long_ror_shift_1 = {4611686018427387904L,-4611686018427387905L,7240608802505390223L,-4285866096636113521L,1402050278713035010L,4701619872251988767L,1175453754738481590L,6854109717816658426L,4692819900234883848L,-877097542178746898L,2573386988543819666L,3139345369587661183L,-2595402128374960063L,8344463203679027341L,-895606656276445163L,5019829197870603170L,-3718635284056663691L,-1883732777196067366L,6652050965850382125L,-3308340109978947729L,-3128235535005369595L,-1682550117343924512L,6188776832920749055L,-5386389167340512578L,4476352830994464300L,-2359445418876316141L,1550213062661043755L,-7725292539043595921L,-8851859160791606721L,-6327585900130377072L,-3150076448693843188L,-3902148238940891987L};

    static final long [] ref_long_ror_shift_127 = {1L,-2L,-7931052937397542339L,1303279687165097535L,5608201114852140040L,359735415298403453L,4701815018953926360L,8969694797557082089L,324535527229983777L,-3508390168714987589L,-8153196119534272952L,-5889362595358906884L,8065135560209711367L,-3515635332702993867L,-3582426625105780649L,1632572717772861065L,3572202937482896855L,-7534931108784269461L,8161459789691976885L,5213383633793760703L,5933801933688073239L,-6730200469375698045L,6308363257973444605L,-3098812595652498694L,-541332749731694416L,9008962398204287055L,6200852250644175020L,5992317991244719550L,1486051504252676350L,-6863599526811956670L,5846438278934178867L,2838151117945983671L};


    static final long [] ref_long_ror_shift_128 = {-9223372036854775808L,9223372036854775807L,-3965526468698771170L,-8571732193272227041L,2804100557426070020L,-9043504329205574082L,2350907509476963180L,-4738524638076234764L,-9061104273239783920L,-1754195084357493795L,5146773977087639332L,6278690739175322366L,-5190804256749920125L,-1757817666351496934L,-1791213312552890325L,-8407085677968345276L,-7437270568113327381L,-3767465554392134731L,-5142642142008787366L,-6616680219957895457L,-6256471070010739189L,-3365100234687849023L,-6069190407868053506L,7673965739028526461L,8952705661988928600L,-4718890837752632281L,3100426125322087510L,2996158995622359775L,743025752126338175L,5791572273448797473L,-6300152897387686375L,-7804296477881783973L};


    static final long [] ref_long_ror_shift_M128 = {-9223372036854775808L,9223372036854775807L,-3965526468698771170L,-8571732193272227041L,2804100557426070020L,-9043504329205574082L,2350907509476963180L,-4738524638076234764L,-9061104273239783920L,-1754195084357493795L,5146773977087639332L,6278690739175322366L,-5190804256749920125L,-1757817666351496934L,-1791213312552890325L,-8407085677968345276L,-7437270568113327381L,-3767465554392134731L,-5142642142008787366L,-6616680219957895457L,-6256471070010739189L,-3365100234687849023L,-6069190407868053506L,7673965739028526461L,8952705661988928600L,-4718890837752632281L,3100426125322087510L,2996158995622359775L,743025752126338175L,5791572273448797473L,-6300152897387686375L,-7804296477881783973L};

    static final long [] ref_long_ror_shift_M129 = {1L,-2L,-7931052937397542339L,1303279687165097535L,5608201114852140040L,359735415298403453L,4701815018953926360L,8969694797557082089L,324535527229983777L,-3508390168714987589L,-8153196119534272952L,-5889362595358906884L,8065135560209711367L,-3515635332702993867L,-3582426625105780649L,1632572717772861065L,3572202937482896855L,-7534931108784269461L,8161459789691976885L,5213383633793760703L,5933801933688073239L,-6730200469375698045L,6308363257973444605L,-3098812595652498694L,-541332749731694416L,9008962398204287055L,6200852250644175020L,5992317991244719550L,1486051504252676350L,-6863599526811956670L,5846438278934178867L,2838151117945983671L};

    static void verify(String text, long ref, long actual) {
      if (ref != actual) {
        System.err.println(text + " " +  ref + " != " + actual);
        throw new Error("Fail");
      }
    }

    public static int [] init_shift_vector(Random rand) {
      int [] vec_int = new int [SIZE];
      vec_int[0] = 127;
      vec_int[1] = -128;
      vec_int[2] = 128;
      vec_int[3] = -129;
      for (int i = 4 ; i < SIZE ; i++) {
        vec_int[i] = rand.nextInt(256);
      }
      return vec_int;
    }

    public static int [] init_int_vector() {
      int [] vec_int = new int [SIZE];
      vec_int[0] = Integer.MIN_VALUE;
      vec_int[1] = Integer.MAX_VALUE;
      for (int i = 2 ; i < SIZE ; i++) {
        vec_int[i] = rand.nextInt();
      }
      return vec_int;
    }

    public static long [] init_long_vector() {
      long [] vec_long = new long [SIZE];
      vec_long[0] = Long.MIN_VALUE;
      vec_long[1] = Long.MAX_VALUE;
      for (int i = 2 ; i < SIZE ; i++) {
        vec_long[i] = rand.nextLong();
      }
      return vec_long;
    }

    public static void test_rol_int(int val, int shift, int index) {
      int actual = Integer.rotateLeft(val, shift);
      verify("Integer.rotateLeft shift = " + shift, ref_rol_int[index], actual);
      actual = (val << shift) | (val >>> -shift);
      verify("Pattern1 integer rotateLeft shift = " + shift, ref_rol_int[index], actual);
      actual = (val << shift) | (val >>> 32-shift);
      verify("Pattern2 integer rotateLeft shift = " + shift, ref_rol_int[index], actual);
    }

    public static void test_ror_int(int val, int shift, int index) {
      int actual = Integer.rotateRight(val, shift);
      verify("Integer.rotateRight shift = " + shift, ref_ror_int[index], actual);
      actual = (val >>> shift) | (val <<-shift);
      verify("Pattern1 integer rotateRight shift = " + shift, ref_ror_int[index], actual);
      actual = (val >>> shift) | (val <<-32-shift);
      verify("Pattern2 integer rotateRight shift = " + shift, ref_ror_int[index], actual);
    }

    public static void test_rol_long(long val, int shift, int index) {
      long actual = Long.rotateLeft(val, shift);
      verify("Long.rotateLeft shift = " + shift, ref_rol_long[index], actual);
      actual =  (val << shift) | (val >>>-shift);
      verify("Pattern1 long rotateLeft shift = " + shift, ref_rol_long[index], actual);
      actual =  (val << shift) | (val >>>64-shift);
      verify("Pattern2 long rotateLeft shift = " + shift, ref_rol_long[index], actual);
    }

    public static void test_ror_long(long val, int shift, int index) {
      long actual = Long.rotateRight(val, shift);
      verify("Long.rotateRight shift = " + shift, ref_ror_long[index], actual);
      actual =  (val >>> shift) | (val <<-shift);
      verify("Pattern1 long rotateRight shift = " + shift, ref_ror_long[index], actual);
      actual =  (val >>> shift) | (val <<64-shift);
      verify("Pattern2 long rotateRight shift = " + shift, ref_ror_long[index], actual);
    }

    public static void test_rol_int_const(int val, int index) {
      int res1 = Integer.rotateLeft(val, 1);
      verify("Constant integer rotateLeft shift = 1", res1 , ref_int_rol_shift_1[index]);
      int res2 = (val << 1) | (val >>> -1);
      verify("Constant integer rotateLeft shift = 1", res2 , ref_int_rol_shift_1[index]);

      res1 = Integer.rotateLeft(val, 127);
      verify("Constant integer rotateLeft shift = 127", res1 , ref_int_rol_shift_127[index]);
      res2 = (val << 127) | (val >>> -127);
      verify("Constant integer rotateLeft shift = 127", res2 , ref_int_rol_shift_127[index]);

      res1 = Integer.rotateLeft(val, 128);
      verify("Constant integer rotateLeft shift = 128", res1 , ref_int_rol_shift_128[index]);
      res2 = (val << 128) | (val >>> -128);
      verify("Constant integer rotateLeft pattern = 128", res2 , ref_int_rol_shift_128[index]);

      res1 = Integer.rotateLeft(val, -128);
      verify("Constant integer rotateLeft shift = -128", res1 , ref_int_rol_shift_M128[index]);
      res2 = (val << -128) | (val >>> 128);
      verify("Constant integer rotateLeft pattern = 128", res2 , ref_int_rol_shift_M128[index]);

      res1 = Integer.rotateLeft(val, -129);
      verify("Constant integer rotateLeft shift = -129", res1 , ref_int_rol_shift_M129[index]);
      res2 = (val << -129) | (val >>> 129);
      verify("Constant integer rotateLeft pattern = 129", res2 , ref_int_rol_shift_M129[index]);
    }

    public static void test_ror_int_const(int val, int index) {
      int res1 = Integer.rotateRight(val, 1);
      verify("Constant integer rotateRight shift = 1", res1 , ref_int_ror_shift_1[index]);
      int res2 = (val >>> 1) | (val << -1);
      verify("Constant integer rotateRight pattern = 1", res2 , ref_int_ror_shift_1[index]);

      res1 = Integer.rotateRight(val, 127);
      verify("Constant integer rotateRight shift = 127", res1 , ref_int_ror_shift_127[index]);
      res2 = (val >>> 127) | (val << -127);
      verify("Constant integer rotateRight pattern = 127", res2 , ref_int_ror_shift_127[index]);

      res1 = Integer.rotateRight(val, 128);
      verify("Constant integer rotateRight shift = 128", res1 , ref_int_ror_shift_128[index]);
      res2 = (val >>> 128) | (val << -128);
      verify("Constant integer rotateRight pattern = 128", res2 , ref_int_ror_shift_128[index]);

      res1 = Integer.rotateRight(val, -128);
      verify("Constant integer rotateRight shift = -128", res1 , ref_int_ror_shift_M128[index]);
      res2 = (val >>> -128) | (val << 128);
      verify("Constant integer rotateRight pattern = 128", res2 , ref_int_ror_shift_M128[index]);

      res1 = Integer.rotateRight(val, -129);
      verify("Constant integer rotateRight shift = -129", res1 , ref_int_ror_shift_M129[index]);
      res2 = (val >>> -129) | (val << 129);
      verify("Constant integer rotateRight pattern = 129", res2 , ref_int_ror_shift_M129[index]);
    }

    public static void test_rol_long_const(long val, int index) {
      long res1 = Long.rotateLeft(val, 1);
      verify("Constant long rotateLeft shift = 1", res1 , ref_long_rol_shift_1[index]);
      long res2 = (val << 1) | (val >>> -1);
      verify("Constant long rotateLeft pattern = 1", res2 , ref_long_rol_shift_1[index]);

      res1 = Long.rotateLeft(val, 127);
      verify("Constant long rotateLeft shift = 127", res1 , ref_long_rol_shift_127[index]);
      res2 = (val << 127) | (val >>> -127);
      verify("Constant long rotateLeft pattern = 127", res2 , ref_long_rol_shift_127[index]);

      res1 = Long.rotateLeft(val, 128);
      verify("Constant long rotateLeft shift = 128", res1 , ref_long_rol_shift_128[index]);
      res2 = (val << 128) | (val >>> -128);
      verify("Constant long rotateLeft pattern = 128", res2 , ref_long_rol_shift_128[index]);

      res1 = Long.rotateLeft(val, -128);
      verify("Constant long rotateLeft shift = -128", res1 , ref_long_rol_shift_M128[index]);
      res2 = (val << -128) | (val >>> 128);
      verify("Constant long rotateLeft pattern = 128", res2 , ref_long_rol_shift_M128[index]);

      res1 = Long.rotateLeft(val, -129);
      verify("Constant long rotateLeft shift = -129", res1 , ref_long_rol_shift_M129[index]);
      res2 = (val << -129) | (val >>> 129);
      verify("Constant long rotateLeft pattern = 129", res2 , ref_long_rol_shift_M129[index]);
    }

    public static void test_ror_long_const(long val, int index) {
      long res1 = Long.rotateRight(val, 1);
      verify("Constant long rotateRight shift = 1", res1 , ref_long_ror_shift_1[index]);
      long res2 = (val >>> 1) | (val << -1);
      verify("Constant long rotateRight pattern = 1", res2 , ref_long_ror_shift_1[index]);

      res1 = Long.rotateRight(val, 127);
      verify("Constant long rotateRight shift = 127", res1 , ref_long_ror_shift_127[index]);
      res2 = (val >>> 127) | (val << -127);
      verify("Constant long rotateRight pattern = 127", res2 , ref_long_ror_shift_127[index]);

      res1 = Long.rotateRight(val, 128);
      verify("Constant long rotateRight shift = 128", res1 , ref_long_ror_shift_128[index]);
      res2 = (val >>> 128) | (val << -128);
      verify("Constant long rotateRight pattern = 128", res2 , ref_long_ror_shift_128[index]);

      res1 = Long.rotateRight(val, -128);
      verify("Constant long rotateRight shift = -128", res1 , ref_long_ror_shift_M128[index]);
      res2 = (val >>> -128) | (val << 128);
      verify("Constant long rotateRight pattern = 128", res2 , ref_long_ror_shift_M128[index]);

      res1 = Long.rotateRight(val, -129);
      verify("Constant long rotateRight shift = -129", res1 , ref_long_ror_shift_M129[index]);
      res2 = (val >>> -129) | (val << 129);
      verify("Constant long rotateRight pattern = 129", res2 , ref_long_ror_shift_M129[index]);
    }

    public static void test_rol_int_zero(int val) {
        // Count is known to be zero only after loop opts
        int count = 42;
        for (int i = 0; i < 4; i++) {
            if ((i % 2) == 0) {
                count = 0;
            }
        }
        int res = Integer.rotateLeft(val, count);
        if (res != val) {
            throw new RuntimeException("test_rol_int_zero failed: " + res + " != " + val);
        }
    }

    public static void test_rol_long_zero(long val) {
        // Count is known to be zero only after loop opts
        int count = 42;
        for (int i = 0; i < 4; i++) {
            if ((i % 2) == 0) {
                count = 0;
            }
        }
        long res = Long.rotateLeft(val, count);
        if (res != val) {
            throw new RuntimeException("test_rol_long_zero failed: " + res + " != " + val);
        }
    }

    public static void test_ror_int_zero(int val) {
        // Count is known to be zero only after loop opts
        int count = 42;
        for (int i = 0; i < 4; i++) {
            if ((i % 2) == 0) {
                count = 0;
            }
        }
        int res = Integer.rotateRight(val, count);
        if (res != val) {
            throw new RuntimeException("test_ror_int_zero failed: " + res + " != " + val);
        }
    }

    public static void test_ror_long_zero(long val) {
        // Count is known to be zero only after loop opts
        int count = 42;
        for (int i = 0; i < 4; i++) {
            if ((i % 2) == 0) {
                count = 0;
            }
        }
        long res = Long.rotateRight(val, count);
        if (res != val) {
            throw new RuntimeException("test_ror_long_zero failed: " + res + " != " + val);
        }
    }

    public static void main(String args[]) throws Exception {
      rand = new Random(8248830);

      int [] test_int = init_int_vector();
      long [] test_long = init_long_vector();
      int [] shift_vec = init_shift_vector(rand);

      try {
        for (int i = 0  ; i < ITERS; i++) {
          for (int j = 0 ; j <  SIZE ; j++) {
            test_rol_int(test_int[j], shift_vec[j], j);
            test_ror_int(test_int[j], shift_vec[j], j);
            test_rol_long(test_long[j], shift_vec[j], j);
            test_ror_long(test_long[j], shift_vec[j], j);

            test_rol_int_const(test_int[j], j);
            test_ror_int_const(test_int[j], j);
            test_rol_long_const(test_long[j], j);
            test_ror_long_const(test_long[j], j);
          }
          test_rol_int_zero(i);
          test_rol_long_zero(i);
          test_ror_int_zero(i);
          test_ror_long_zero(i);
        }
        System.out.println("test status : PASS");
      } catch (Exception e) {
        System.out.println(e.getMessage());
      }
    }
}
