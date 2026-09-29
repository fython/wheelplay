#include "wheelplay_send_result.hpp"
#include <cassert>
#include <vector>
int main() {
    std::vector<int> packets{1,2,3};
    int sent = 0;
    assert(!wheelplay::sendAll(packets, [&](int n) { ++sent; return n != 2; }));
    assert(sent == 3); // A successful final packet must not hide a middle failure.
    assert(wheelplay::sendAll(packets, [](int) { return true; }));
    assert(!wheelplay::sendAll(packets, [](int) { return false; }));
    std::vector<int> empty;
    assert(!wheelplay::sendAll(empty, [](int) { return true; }));
}
