package com.neotalkie.device;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Offline regression checks for the five-minute contract, restart and text truncation. */
public final class ConversationMemoryTest {
  private static int checks;

  private static void ok(boolean value) {
    checks++;
    if (!value) throw new AssertionError("history check " + checks);
  }

  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("neotalkie-memory-");
    File file = dir.resolve("conversation.bin").toFile();
    long start = 1000000;
    try {
      ConversationMemory memory = new ConversationMemory(file);
      ok(memory.snapshot(start).isEmpty());
      memory.remember("我的代号是蓝鲸", "记住了。", start);
      // A failed/cancelled/empty turn never remembers anything; reads preserve the history.
      ok(memory.snapshot(start + 10000).size() == 1);
      memory.remember("", "", start + 20000);
      ok(memory.snapshot(start + 30000).size() == 1);
      // Recreating the activity/process must restore the same text, without a response ID.
      memory = new ConversationMemory(file);
      ok(memory.snapshot(start + 299999).get(0)[0].equals("我的代号是蓝鲸"));
      List<String[]> copy = memory.snapshot(start + 299999);
      copy.get(0)[0] = "mutated";
      ok(memory.snapshot(start + 299999).get(0)[0].equals("我的代号是蓝鲸"));
      ok(memory.snapshot(start + 300000).isEmpty());
      ok(!file.exists());

      memory.remember("解释安康鱼", new String(new char[120]).replace('\0', '鱼'), start);
      // Text-capped replies are still usable history; each completed turn renews idle expiry.
      ok(new ConversationMemory(file).snapshot(start + 1).get(0)[1].length() == 120);
      memory.snapshot(start + 299999);
      memory.remember("它生活在哪里", "海底。", start + 310000);
      ok(new ConversationMemory(file).snapshot(start + 600000).size() == 2);
      ok(memory.snapshot(start + 610000).isEmpty());
      memory.remember("a", "b", start);
      ok(memory.snapshot(start - 1).isEmpty()); // Conservative expiry on clock rollback.

      memory.remember("我给潜水艇取名叫蓝鲸", "好的。", start);
      memory.rememberInterrupted("介绍安康鱼", "安康鱼是一种海鱼", start + 1000);
      memory = new ConversationMemory(file);
      List<String[]> interrupted = memory.snapshot(start + 2000);
      ok(interrupted.size() == 2);
      ok(interrupted.get(0)[0].contains("蓝鲸"));
      ok(interrupted.get(1)[0].equals("介绍安康鱼"));
      ok(interrupted.get(1)[1].startsWith("安康鱼是一种海鱼"));
      ok(interrupted.get(1)[1].contains("不代表用户已听完"));
      memory.remember("它住在哪里", "海底。", start + 3000);
      ok(memory.snapshot(start + 4000).size() == 3);
      // Stopping a replay or pre-ASR input cannot create a fake conversational turn.
      memory.rememberInterrupted("", "旧录音", start + 5000);
      ok(memory.snapshot(start + 6000).size() == 3);
      memory.rememberInterrupted("还在吗", "", start + 7000);
      ok(memory.snapshot(start + 8000).get(3)[1].contains("尚未回答"));
      ok(memory.snapshot(start + 306999).size() == 4);
      ok(memory.snapshot(start + 307000).isEmpty());

      memory.remember("old user", "old reply", start);
      long oldGeneration = memory.generation();
      ok(memory.remainingMillis(start + 1) == 299999);
      memory.clear();
      ok(memory.remainingMillis(start + 2) == 0);
      ok(new ConversationMemory(file).snapshot(start + 2).isEmpty());
      memory.rememberIfCurrent(oldGeneration, "late user", "late reply", start + 3, false);
      memory.rememberIfCurrent(oldGeneration, "late interrupted", "reply", start + 4, true);
      ok(memory.snapshot(start + 5).isEmpty());
      memory.rememberIfCurrent(memory.generation(), "new user", "new reply", start + 6, false);
      ok(memory.snapshot(start + 7).size() == 1);
      ok(memory.remainingMillis(start + 300006) == 0);
      ok(new ConversationMemory(file).snapshot(start + 300006).isEmpty());

      for (int i = 0; i < 40; i++) {
        memory.snapshot(start + i);
        memory.remember("turn " + i, "reply", start + i);
      }
      ok(memory.snapshot(start + 40).size() == ConversationMemory.MAX_TURNS);
      ok(memory.snapshot(start + 40).get(0)[0].equals("turn 8"));
      String large = new String(new char[5000]).replace('\0', '测');
      for (int i = 0; i < 10; i++) memory.remember(large, large, start + 50 + i);
      List<String[]> bounded = new ConversationMemory(file).snapshot(start + 60);
      ok(bounded.size() == 4);
      ok(bounded.get(0)[0].length() == 4000);
      Files.write(file.toPath(), new byte[] {1, 2, 3});
      ok(new ConversationMemory(file).snapshot(start).isEmpty());
      ok(!file.exists());
      System.out.println("Offline conversation memory checks passed: " + checks);
    } finally {
      file.delete();
      new File(file.getPath() + ".tmp").delete();
      Files.delete(dir);
    }
  }
}
