<script setup lang="ts">
defineProps<{ active?: number | null }>()
const emit = defineEmits<{ greet: [index: number] }>()
</script>
<template>
  <svg
    viewBox="70 0 840 310"
    role="group"
    aria-label="五位原创 WAO 伙伴：灵感星星、记录小本、AI 助手、行动闪电、资料方块"
  >
    <defs>
      <filter id="blur"><feGaussianBlur stdDeviation="14" /></filter>
    </defs>
    <g opacity=".16" filter="url(#blur)">
      <ellipse cx="190" cy="273" rx="60" ry="13" fill="#ffd964" />
      <ellipse cx="340" cy="273" rx="65" ry="13" fill="#b0a0ff" />
      <ellipse cx="516" cy="276" rx="100" ry="19" fill="#57dace" />
      <ellipse cx="704" cy="274" rx="60" ry="13" fill="#ff7fb2" />
      <ellipse cx="842" cy="274" rx="55" ry="12" fill="#aad873" />
    </g>
    <g
      class="buddy"
      role="button"
      tabindex="0"
      aria-label="灵感：留住每个一闪而过的念头"
      :class="{ greeting: active === 0 }"
      @pointerenter="$event.pointerType === 'mouse' && emit('greet', 0)"
      @focus="emit('greet', 0)"
      @click="emit('greet', 0)"
      @keydown.enter.prevent="emit('greet', 0)"
      @keydown.space.prevent="emit('greet', 0)"
    >
      <title>灵感：留住每个一闪而过的念头</title>
      <path
        d="M165 222l-8 33h-21m63-32 9 32h20"
        stroke="#edb844"
        stroke-width="13"
        stroke-linecap="round"
        fill="none"
      />
      <path
        d="m130 167-27 17-14-20m143 6 25-23"
        stroke="#f8d371"
        stroke-width="12"
        stroke-linecap="round"
        fill="none"
      />
      <path
        d="m181 98 21 39 45 5-31 34 8 47-43-21-43 21 8-47-31-34 45-5z"
        fill="#ffdc70"
        stroke="#ffe497"
        stroke-width="3"
      />
      <ellipse cx="170" cy="164" rx="5" ry="8" fill="#3e3020" />
      <ellipse cx="194" cy="164" rx="5" ry="8" fill="#3e3020" />
      <path
        d="M174 181q9 9 18-1"
        stroke="#3e3020"
        stroke-width="3"
        fill="none"
        stroke-linecap="round"
      />
      <path
        d="m114 107-8-13m125 17 11-9m-63-17V73"
        stroke="#ffe09b"
        stroke-width="3"
        stroke-linecap="round"
      />
      <text class="label" x="158" y="299">灵 感</text>
    </g>
    <g
      class="buddy"
      role="button"
      tabindex="0"
      aria-label="记录：随时记下，慢慢长大"
      :class="{ greeting: active === 1 }"
      @pointerenter="$event.pointerType === 'mouse' && emit('greet', 1)"
      @focus="emit('greet', 1)"
      @click="emit('greet', 1)"
      @keydown.enter.prevent="emit('greet', 1)"
      @keydown.space.prevent="emit('greet', 1)"
    >
      <title>记录：随时记下，慢慢长大</title>
      <path
        d="m303 222-4 33h-16m69-33 4 33h17"
        stroke="#9880e1"
        stroke-width="13"
        stroke-linecap="round"
        fill="none"
      />
      <path
        d="m286 171-25 29m107-28 19-20 16 8"
        stroke="#bba5fb"
        stroke-width="12"
        stroke-linecap="round"
        fill="none"
      />
      <g transform="rotate(-7 329 180)">
        <rect x="283" y="121" width="93" height="117" rx="20" fill="#ad94ee" />
        <path d="M300 125v109" stroke="#d8c9ff" stroke-width="3" />
        <path
          d="M277 144h13m-13 20h13m-13 20h13m-13 20h13"
          stroke="#ded4fa"
          stroke-width="5"
          stroke-linecap="round"
        />
        <rect x="315" y="135" width="42" height="6" rx="3" fill="#ddd0ff" />
        <ellipse cx="322" cy="177" rx="5" ry="7" fill="#342549" />
        <ellipse cx="349" cy="177" rx="5" ry="7" fill="#342549" />
        <path
          d="M326 196q10 9 19-2"
          stroke="#342549"
          stroke-width="3"
          fill="none"
          stroke-linecap="round"
        />
      </g>
      <text class="label" x="307" y="299">记 录</text>
    </g>
    <g
      class="buddy"
      role="button"
      tabindex="0"
      aria-label="AI 伙伴：一起拆解目标，开始行动"
      :class="{ greeting: active === 2 }"
      @pointerenter="$event.pointerType === 'mouse' && emit('greet', 2)"
      @focus="emit('greet', 2)"
      @click="emit('greet', 2)"
      @keydown.enter.prevent="emit('greet', 2)"
      @keydown.space.prevent="emit('greet', 2)"
    >
      <title>AI 伙伴：一起拆解目标，开始行动</title>
      <path
        d="M464 229v26h-28m126-26v26h28"
        stroke="#48b9ab"
        stroke-width="20"
        stroke-linecap="round"
        fill="none"
      />
      <path
        d="m430 165-23 43m179-43 24 42"
        stroke="#62dacb"
        stroke-width="22"
        stroke-linecap="round"
      />
      <path d="M510 84V62" stroke="#7aefda" stroke-width="6" />
      <circle cx="510" cy="54" r="10" fill="#e9ffae" />
      <rect x="429" y="89" width="158" height="156" rx="58" fill="#65dfcf" />
      <path d="M446 124q63-37 123 0" stroke="#97f1df" stroke-width="4" fill="none" />
      <rect x="451" y="132" width="112" height="57" rx="27" fill="#123e3c" />
      <ellipse cx="482" cy="158" rx="8" ry="12" fill="#c2ffed" />
      <ellipse cx="533" cy="158" rx="8" ry="12" fill="#c2ffed" />
      <path d="M501 168q7 6 14 0" stroke="#c2ffed" stroke-width="3" fill="none" />
      <path d="M479 210h57" stroke="#30a995" stroke-width="3" />
      <text x="487" y="231" fill="#237769" font-size="13" font-weight="bold" letter-spacing="2">
        wao
      </text>
      <path d="m574 78 5-13 5 13 13 5-13 5-5 13-5-13-13-5z" fill="#d9fa9f" />
      <text class="label" x="482" y="299">AI 伙 伴</text>
    </g>
    <g
      class="buddy"
      role="button"
      tabindex="0"
      aria-label="行动：让好想法真正发生"
      :class="{ greeting: active === 3 }"
      @pointerenter="$event.pointerType === 'mouse' && emit('greet', 3)"
      @focus="emit('greet', 3)"
      @click="emit('greet', 3)"
      @keydown.enter.prevent="emit('greet', 3)"
      @keydown.space.prevent="emit('greet', 3)"
    >
      <title>行动：让好想法真正发生</title>
      <path
        d="m672 222-9 34h-19m67-35 12 35h16"
        stroke="#d95c92"
        stroke-width="12"
        stroke-linecap="round"
        fill="none"
      />
      <path
        d="m650 163-26-22m101 25 17-19 7-24"
        stroke="#fa8db9"
        stroke-width="12"
        stroke-linecap="round"
        fill="none"
      />
      <path
        d="m699 95-59 84h38l-7 64 68-94h-43l20-54z"
        fill="#ff91bf"
        stroke="#ffa8cc"
        stroke-width="3"
      />
      <ellipse cx="682" cy="165" rx="4" ry="6" fill="#572139" />
      <ellipse cx="707" cy="165" rx="4" ry="6" fill="#572139" />
      <path d="M686 183q7 7 14-2" stroke="#572139" stroke-width="3" fill="none" />
      <path d="m759 105 7-9m-12-3 1-10" stroke="#ffbdde" stroke-width="3" stroke-linecap="round" />
      <text class="label" x="674" y="299">行 动</text>
    </g>
    <g
      class="buddy"
      role="button"
      tabindex="0"
      aria-label="资料：让积累随时可用"
      :class="{ greeting: active === 4 }"
      @pointerenter="$event.pointerType === 'mouse' && emit('greet', 4)"
      @focus="emit('greet', 4)"
      @click="emit('greet', 4)"
      @keydown.enter.prevent="emit('greet', 4)"
      @keydown.space.prevent="emit('greet', 4)"
    >
      <title>资料：让积累随时可用</title>
      <path
        d="m817 225-5 31h-15m56-32 3 32h15"
        stroke="#90b653"
        stroke-width="12"
        stroke-linecap="round"
        fill="none"
      />
      <path
        d="m795 184-23 10m96-11 18-19"
        stroke="#b4d97b"
        stroke-width="11"
        stroke-linecap="round"
        fill="none"
      />
      <path
        d="M793 151q0-13 13-13h27l9 12h19q15 0 15 15v62q0 14-14 14h-55q-14 0-14-14z"
        fill="#c6e496"
      />
      <rect
        x="803"
        y="122"
        width="56"
        height="39"
        rx="5"
        fill="#e7f0d1"
        transform="rotate(7 831 143)"
      />
      <rect x="789" y="160" width="89" height="74" rx="20" fill="#b6d87c" />
      <ellipse cx="817" cy="188" rx="4" ry="6" fill="#384521" />
      <ellipse cx="844" cy="188" rx="4" ry="6" fill="#384521" />
      <path d="M823 207h14" stroke="#384521" stroke-width="3" stroke-linecap="round" />
      <text class="label" x="810" y="299">资 料</text>
    </g>
  </svg>
</template>
<style scoped>
svg {
  display: block;
  width: 100%;
  height: auto;
  overflow: visible;
}
.buddy {
  transition: transform 0.25s;
  transform-box: fill-box;
  transform-origin: 50% 100%;
  cursor: pointer;
}
.buddy.greeting,
.buddy:hover,
.buddy:focus-visible {
  transform: translateY(-8px) rotate(-3deg);
  outline: none;
  filter: brightness(1.15);
}
.label {
  font-size: 10px;
  fill: #aaa5af;
  letter-spacing: 3px;
}
@media (prefers-reduced-motion: reduce) {
  .buddy {
    transition: none;
  }
  .buddy.greeting,
  .buddy:hover,
  .buddy:focus-visible {
    transform: none;
  }
}
</style>
