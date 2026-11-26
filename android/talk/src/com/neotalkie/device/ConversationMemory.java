package com.neotalkie.device;

import java.io.*;
import java.util.*;

/** Small private text history. No credentials, audio, Android APIs or network access. */
final class ConversationMemory {
  static final long IDLE_MS = UiContract.CONTEXT_MS;
  static final int MAX_TURNS = 32, MAX_CHARS = 32000, MAX_TEXT = 4000;
  private final File file;
  private final long idleMs;
  private final ArrayList<String[]> turns = new ArrayList<>();
  private long touched, generation;

  ConversationMemory(File file) {
    this(file,IDLE_MS);
  }
  ConversationMemory(File file,long idleMs) {
    this.idleMs=idleMs;
    this.file = file;
    if (!file.isFile()) return;
    try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
      if (file.length() > 200000 || in.readInt() != 1) throw new IOException("history format");
      touched = in.readLong();
      int count = in.readInt(), chars = 0;
      if (count < 0 || count > MAX_TURNS) throw new IOException("history count");
      for (int i = 0; i < count; i++) {
        String user = in.readUTF(), reply = in.readUTF();
        chars += user.length() + reply.length();
        if (user.isEmpty()
            || reply.isEmpty()
            || user.length() > MAX_TEXT
            || reply.length() > MAX_TEXT
            || chars > MAX_CHARS) throw new IOException("history size");
        turns.add(new String[] {user, reply});
      }
      if (in.read() != -1) throw new IOException("history trailing data");
    } catch (IOException e) {
      turns.clear();
      file.delete();
    }
  }

  private void expire(long now) {
    if (now < touched || now - touched >= idleMs) {
      turns.clear();
      file.delete();
    }
  }

  synchronized long generation() {
    return generation;
  }

  synchronized long remainingMillis(long now) {
    expire(now);
    return turns.isEmpty() ? 0 : idleMs - (now - touched);
  }

  synchronized void clear() throws IOException {
    generation++;
    turns.clear();
    touched = 0;
    save();
  }

  synchronized void rememberIfCurrent(
      long expected, String user, String reply, long now, boolean interrupted) throws IOException {
    if (expected != generation) return;
    if (interrupted) rememberInterrupted(user, reply, now);
    else remember(user, reply, now);
  }

  synchronized List<String[]> snapshot(long now) {
    expire(now);
    ArrayList<String[]> copy = new ArrayList<>();
    for (String[] turn : turns) copy.add(turn.clone());
    return copy;
  }

  // PTT continuation retains the recognized question and generated text, without claiming
  // that all generated text was heard. Other cancellations leave previous turns intact.
  synchronized void rememberInterrupted(String user, String generated, long now)
      throws IOException {
    if (user.isEmpty()) return;
    String note = "\n（用户按 PTT 打断了本轮播报；以上是已生成文字，不代表用户已听完。请接续下一条问题。）";
    if (generated.isEmpty()) note = "（本轮尚未回答，用户按 PTT 追加了下一条问题。）";
    int limit = MAX_TEXT - note.length();
    remember(user, generated.substring(0, Math.min(generated.length(), limit)) + note, now);
  }

  // Called once per turn, after completion or explicit PTT continuation.
  synchronized void remember(String user, String reply, long now) throws IOException {
    if (idleMs==0 || user.isEmpty() || reply.isEmpty()) return;
    turns.add(new String[] {clip(user), clip(reply)});
    int chars = 0;
    for (String[] turn : turns) chars += turn[0].length() + turn[1].length();
    while (turns.size() > MAX_TURNS || chars > MAX_CHARS) {
      String[] old = turns.remove(0);
      chars -= old[0].length() + old[1].length();
    }
    touched = now;
    save();
  }

  private void save() throws IOException {
    File temp = new File(file.getPath() + ".tmp");
    try (FileOutputStream stream = new FileOutputStream(temp);
        DataOutputStream out = new DataOutputStream(stream)) {
      out.writeInt(1);
      out.writeLong(touched);
      out.writeInt(turns.size());
      for (String[] turn : turns) {
        out.writeUTF(turn[0]);
        out.writeUTF(turn[1]);
      }
      out.flush();
      stream.getFD().sync();
    }
    if (!temp.renameTo(file)) throw new IOException("history save");
  }

  private static String clip(String text) {
    return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
  }
}
