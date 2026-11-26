package com.neotalkie.device;

public final class ReplyTextStreamTest {
  public static void main(String[] args) {
    String input = "答案：三。<topic>加法</topic>";
    for (int split = 0; split <= input.length(); split++) {
      ReplyTextStream stream = new ReplyTextStream(120);
      String body = stream.accept(input.substring(0, split)) + stream.accept(input.substring(split));
      if (!"答案：三。".equals(body) || !"加法".equals(stream.topic())) throw new AssertionError(split);
    }
    ReplyTextStream limited = new ReplyTextStream(2);
    StringBuilder body = new StringBuilder();
    for (char c : input.toCharArray()) body.append(limited.accept(String.valueOf(c)));
    if (!"答案".contentEquals(body) || !"加法".equals(limited.topic())) throw new AssertionError();
    for (String suffix : new String[]{"", "<to", "<topic>缺尾", "<topic>一二三四五六七</topic>", "<topic>abc</topic>"}) {
      ReplyTextStream stream = new ReplyTextStream(100);
      if (!"原文".equals(stream.accept("原文" + suffix)) || !stream.topic().isEmpty()) throw new AssertionError(suffix);
    }
    System.out.println("Reply stream split, duration text budget and topic fallback checks passed");
  }
}
