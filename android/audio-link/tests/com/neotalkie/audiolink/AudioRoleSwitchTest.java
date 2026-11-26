package com.neotalkie.audiolink;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AudioRoleSwitchTest {
  private final ArrayDeque<Runnable> worker = new ArrayDeque<>(), main = new ArrayDeque<>();
  private final List<Boolean> applied = new ArrayList<>(), reported = new ArrayList<>();
  private boolean fail;
  private final AudioRoleSwitch roles = new AudioRoleSwitch(worker::add, main::add, open -> {
    applied.add(open);
    if (fail) throw new IOException("switch failed");
  });

  private void request(boolean open) {
    roles.request(open, error -> {
      if ((error != null) != fail) throw new AssertionError(error);
      if (error == null) reported.add(open);
    });
  }

  private void drain(ArrayDeque<Runnable> queue) {
    while (!queue.isEmpty()) queue.remove().run();
  }

  private void expect(List<Boolean> actual, Boolean... expected) {
    if (!actual.equals(Arrays.asList(expected))) throw new AssertionError(actual);
  }

  private void run() {
    request(true);
    request(false);
    drain(worker);
    drain(main);
    expect(applied, false);
    expect(reported, false);

    request(true);
    drain(worker); // The ROM has enabled the role, but the activity leaves before its callback.
    request(false);
    drain(worker);
    request(true); // Reopen while the previous close completion is queued.
    drain(worker);
    drain(main);
    expect(applied, false, true, false, true);
    expect(reported, false, true);

    fail = true;
    request(false);
    drain(worker);
    drain(main);
    fail = false;
    request(false);
    drain(worker);
    drain(main);
    expect(reported, false, true, false);
  }

  public static void main(String[] args) {
    new AudioRoleSwitchTest().run();
    System.out.println("Audio Link lifecycle role transitions passed");
  }
}
