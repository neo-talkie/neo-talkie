package com.neotalkie.device;
public final class PttHandoffTest {
  private static int count;
  private static void check(boolean value) { if(!value)throw new AssertionError("PTT case "+count);count++; }
  public static void main(String[] args) {
    PttHandoff s=new PttHandoff();
    check(s.down(false)); check(!s.down(false)); s.pause();
    check(s.resumeTalk()); check(!s.resumeTalk()); check(!s.down(true));
    s.up(); check(s.down(true)); s.up();
    check(s.down(false)); s.pause(); s.up(); check(!s.resumeTalk());
    check(s.down(true)); s.pause(); check(s.down(true)); s.up();
    check(s.down(false)); s.up(); s.pause(); check(!s.resumeTalk());
    check(s.down(false)); s.pause(); check(!s.down(false)); check(s.resumeTalk());
    s.up(); check(s.down(true)); s.up(); check(!s.resumeTalk());
    System.out.println("Offline PTT handoff checks passed: "+count);
  }
}
