// Ground-truth corpus for the demangler: every shape the Bedrock library uses.
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace Level {
struct Actor {
    virtual ~Actor() {}
    virtual void tick() {}
    void name() const {}
};
void tick() {
    auto lambda = [](int value) { return value + 1; };
    lambda(2);
    std::function<int(int)> f = lambda;
    f(3);
    struct Local {
        int field = 0;
    } local;
    (void)local;
}
}  // namespace Level

namespace entt {
template <typename T>
struct basic_storage {
    T *get() { return nullptr; }
    std::size_t size() const { return 0; }
};
template <typename... Args>
struct ViewT {};
template <typename T>
struct Include {};
template <typename T>
struct Exclude {};
}  // namespace entt

struct PositionComponent {
    float x, y, z;
};
struct ActorOwnerComponent {
    void *actor;
};

template <typename T>
int consume(T value) {
    return static_cast<int>(sizeof(T)) + static_cast<int>(value);
}

static int tickMe(int value) { return value * 2; }

template <int (*Fn)(int)>
struct StrictSystem {
    int call(int value) { return Fn(value); }
};

template <int (*Fn)(int)>
void runSystem() {
    StrictSystem<Fn> system;
    system.call(4);
}

void use_all() {
    Level::Actor actor;
    actor.tick();
    Level::tick();
    entt::basic_storage<PositionComponent> storage;
    storage.get();
    storage.size();
    entt::ViewT<entt::Include<PositionComponent>, entt::Exclude<ActorOwnerComponent>> view;
    (void)view;
    consume(1.5);
    consume(2u);
    runSystem<tickMe>();
    std::map<std::string, std::vector<std::unique_ptr<Level::Actor>>> registry;
    (void)registry;
}
