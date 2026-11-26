package com.neotalkie.audiolink;

import java.io.IOException;
import java.util.concurrent.Executor;

/** Serializes ROM restarts. Only the latest request may publish a result to the UI. */
final class AudioRoleSwitch {
  interface Apply { void run(boolean open) throws IOException, InterruptedException; }
  interface Completion { void finish(String error); }

  private final Executor worker, main;
  private final Apply apply;
  private volatile long revision;

  AudioRoleSwitch(Executor worker, Executor main, Apply apply) {
    this.worker = worker;
    this.main = main;
    this.apply = apply;
  }

  void request(boolean open, Completion completion) {
    long ticket = ++revision;
    worker.execute(() -> {
      if (ticket != revision) return;
      String error = null;
      try {
        apply.run(open);
      } catch (IOException | SecurityException e) {
        error = e.getMessage() == null ? "蓝牙配置失败" : e.getMessage();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        error = "蓝牙切换中断";
      }
      final String result = error;
      main.execute(() -> {
        if (ticket == revision) completion.finish(result);
      });
    });
  }
}
