package com.neotalkie.device;
public final class PttLeaseTest {
  static void check(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) {
    PttLease lease = new PttLease();
    check(!lease.isHeld("cold", 0));
    lease.begin("first", 100);
    check(lease.isHeld("first", 100));
    check(!lease.isHeld("other", 100));
    lease.release("other"); check(lease.isHeld("first", 101));
    lease.release(); check(!lease.isHeld("first", 102));
    lease.begin("second", 200);
    lease.release("first"); check(lease.isHeld("second", 201));
    check(!lease.isHeld("first", 201));
    check(!lease.isHeld("second", 199));
    check(!lease.isHeld("second", 30200));
    lease.release("second"); check(!lease.isHeld("second", 202));
    System.out.println("PTT lease: 10 cold-start/release/token/expiry checks passed");
  }
}
