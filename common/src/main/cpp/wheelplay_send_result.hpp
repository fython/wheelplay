#pragma once
#include <utility>

// Preserve every packet's result: a successful tail packet must not hide an earlier failure.
namespace wheelplay {
template<class Messages, class Send> bool sendAll(Messages& messages, Send send) {
    bool success = !messages.empty();
    for (auto& message : messages) success = send(std::move(message)) && success;
    return success;
}
}
