// Development-only entry: Vite production build includes index.html only.
import { createApp, h, onBeforeUnmount, provide, ref } from "vue";
import GameHost from "../../host/GameHost.vue";
import { createGameMemory, gameMemoryKey } from "../../host/useGameMemory";
import { MemoryStore } from "../../host/memory";

createApp({
  setup() {
    const visibilityLog = ref("尚未发生可见性事件");
    const events: string[] = [];
    const observeVisibility = () => {
      events.push(document.visibilityState);
      visibilityLog.value = events.join(" → ");
    };
    document.addEventListener("visibilitychange", observeVisibility);
    onBeforeUnmount(() => document.removeEventListener("visibilitychange", observeVisibility));
    const failure = ref<"none" | "denied" | "quota">("none");
    const factory = {
      open(...args: Parameters<IDBFactory["open"]>) {
        if (failure.value === "denied")
          throw new DOMException("Injected storage denial", "SecurityError");
        const request = indexedDB.open(...args);
        request.addEventListener("success", () => {
          const db = request.result,
            transaction = db.transaction.bind(db);
          db.transaction = ((
            ...params: Parameters<IDBDatabase["transaction"]>
          ) => {
            if (failure.value === "denied")
              throw new DOMException(
                "Injected storage denial",
                "SecurityError",
              );
            if (failure.value === "quota" && params[1] === "readwrite")
              throw new DOMException(
                "Injected quota failure",
                "QuotaExceededError",
              );
            return transaction(...params);
          }) as IDBDatabase["transaction"];
        });
        return request;
      },
    } as IDBFactory;
    provide(
      gameMemoryKey,
      createGameMemory(
        new MemoryStore(factory, "wao-rift-memory-browser-fault-test"), "rift",
      ),
    );
    return () =>
      h("div", [
        h("aside", { style: "padding:12px;background:#fff1a0" }, [
          h("strong", "开发验收专用 · 注入存储失败，不属于正式游戏入口"),
          h("p", { role: "status" }, "浏览器可见性：" + visibilityLog.value),
          h("a", { href: "/narrow.html", target: "_blank", rel: "noopener" }, "打开另一标签验收后台暂停"),
          h("label", [
            " 存储情景 ",
            h(
              "select",
              {
                value: failure.value,
                onChange: (e: Event) => {
                  failure.value = (e.target as HTMLSelectElement)
                    .value as typeof failure.value;
                },
              },
              [
                h("option", { value: "none" }, "正常存储"),
                h("option", { value: "quota" }, "模拟空间不足"),
                h("option", { value: "denied" }, "模拟存储拒绝"),
              ],
            ),
          ]),
        ]),
        h(GameHost, { active: true, gameId: "rift", source: "/index.html?qa=performance" }),
      ]);
  },
}).mount("#app");
