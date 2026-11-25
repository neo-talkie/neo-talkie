package com.neotalkie.device;

import java.util.Arrays;

/** Offline wrapping boundaries, truncation and Unicode checks. */
public final class TurnTextTest {
  private static int checks;
  private static final TurnText.Measure THREE =
      new TurnText.Measure() {
        public int fit(String text) {
          return text.offsetByCodePoints(0, Math.min(3, text.codePointCount(0, text.length())));
        }
      };

  private static void equal(String[] expected, String[] actual) {
    checks++;
    if (!Arrays.equals(expected, actual)) throw new AssertionError(Arrays.toString(actual));
    for (String line : actual) {
      if (THREE.fit(line) != line.length()) throw new AssertionError("overflow");
      for (int i = 0; i < line.length(); i++) {
        char ch = line.charAt(i);
        if (Character.isHighSurrogate(ch)) {
          if (++i == line.length() || !Character.isLowSurrogate(line.charAt(i)))
            throw new AssertionError("broken surrogate");
        } else if (Character.isLowSurrogate(ch)) throw new AssertionError("broken surrogate");
      }
    }
  }

  public static void main(String[] args) {
    TurnText.Document shortText = TurnText.document("就绪", "你好", THREE);
    if (shortText.expanded()) throw new AssertionError("two rows retain buttons");
    equal(new String[] {"就绪", "你好", "", ""}, shortText.window(0));
    TurnText.Document system = TurnText.document("识别中", "这是一条意外过长的系统提示", THREE);
    equal(new String[] {"识别中", "这是…", "", ""}, system.window(0));
    check(!system.expanded() && system.maxOffset() == 0);
    equal(new String[] {"正在…", "你好", "", ""}, TurnText.document("正在回答", "你好", THREE).window(0));
    equal(new String[] {"", "", "", ""}, TurnText.document(null, null, THREE).window(0));
    equal(new String[] {"听", "a b", "", ""}, TurnText.document("听", "a\nb", THREE).window(0));
    TurnText.Document three = TurnText.document("答", "一二三四五六七八九", THREE, true);
    check(three.expanded() && three.maxOffset() == 0);
    equal(new String[] {"一二三", "四五六", "七八九", ""}, three.window(0));
    TurnText.Document longText = TurnText.document("答", "一二三四五六七八九十甲乙丙丁戊己庚辛", THREE, true);
    check(longText.maxOffset() == 2);
    equal(new String[] {"一二三", "四五六", "七八九", "十甲乙"}, longText.window(0));
    equal(new String[] {"四五六", "七八九", "十甲乙", "丙丁戊"}, longText.window(1));
    equal(new String[] {"七八九", "十甲乙", "丙丁戊", "己庚辛"}, longText.window(2));
    equal(longText.window(0), longText.window(-100));
    equal(longText.window(2), longText.window(100));
    TurnText.Document extended = TurnText.document("答", "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸", THREE, true);
    equal(longText.window(1), extended.window(1));
    TurnText.Document emoji = TurnText.document("答", "😀😁😂😃😄😅😆😇😈😉😊😋", THREE, true);
    equal(new String[] {"😀😁😂", "😃😄😅", "😆😇😈", "😉😊😋"}, emoji.window(0));
    check(String.join("", emoji.lines).equals("😀😁😂😃😄😅😆😇😈😉😊😋"));
    equal(new String[] {"你好", "", "", ""}, TurnText.document("正在回答", "你好", THREE, true).window(0));
    equal(new String[] {"合成中", "", "", ""}, TurnText.document("合成中", "", THREE, true).window(0));
    TurnText.Document answer = TurnText.document("回答结束", "一二三四五六七八九十甲乙", THREE, true);
    equal(new String[] {"一二三", "四五六", "七八九", "十甲乙"}, answer.window(0));
    check(answer.maxOffset() == 0 && answer.expanded());
    check(!TurnText.document("合成中", "一二三四五六", THREE, true).expanded());
    for (String state : new String[] {"正在听", "识别中", "思考中", "联网查询", "合成中"}) {
      TurnText.Document status = TurnText.processing(state, THREE);
      equal(
          new String[] {
            TurnText.ellipsis(state, THREE), state.equals("正在听") ? "松开…" : "请稍候", "", ""
          },
          status.window(0));
      check(!status.expanded() && status.maxOffset() == 0);
    }
    check(TurnText.processingStage("正在听") == 1);
    check(TurnText.processingStage("识别中") == 2);
    check(TurnText.processingStage("联网查询") == 3);
    check(TurnText.processingStage("合成中") == 4);
    check(TurnText.processingStage("正在回答") == 0);
    TurnText.FooterFade fade = new TurnText.FooterFade();
    check(fade.alpha(0) == 255 && !fade.running(0));
    fade.setExpanded(true, 1000);
    check(fade.alpha(1000) == 255 && fade.running(1000));
    check(fade.alpha(1050) == 128);
    fade.setExpanded(true, 1050); // Streaming updates must not restart the fade.
    check(fade.alpha(1100) == 0 && !fade.running(1100));
    fade.setExpanded(false, 1200);
    check(fade.alpha(1250) == 127);
    check(fade.alpha(1300) == 255 && !fade.running(1300));
    fade.setExpanded(true, 1400);
    fade.setExpanded(false, 1450); // Reverse continuously if content gets short again.
    check(fade.alpha(1450) == 128);
    check(fade.alpha(1500) == 191);
    check(fade.alpha(1550) == 255 && !fade.running(1550));
    System.out.println("Offline turn text/fade checks passed: " + checks);
  }

  private static void check(boolean pass) {
    checks++;
    if (!pass) throw new AssertionError("fade check " + checks);
  }
}
