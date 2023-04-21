package de.kontext_e.idea.plugins.autofill;

public class ClassA {

  public ClassA(int x) {
  }
  public ClassA(int x, int y) {
  }

  void callee(int x) {}

  void callee(int x, int y) {}

  void caller() {
    int x = 0;
    int y = 0;
    callee(x, y);
  }
}
