<script setup lang="ts">
import { computed } from "vue";
import { species, type BeastId } from "./catalog";
const props = withDefaults(
  defineProps<{
    kind: BeastId | "guardian" | "healer";
    evolved?: boolean;
    region?: number;
    hurt?: boolean;
    casting?: boolean;
    asleep?: boolean;
  }>(),
  { region: 0 },
);
const info = computed(() =>
  props.kind === "guardian" || props.kind === "healer"
    ? null
    : species[props.kind],
);
const color = computed(
  () =>
    info.value?.color ??
    (props.kind === "healer"
      ? "#efc06e"
      : ["#78a16a", "#72a8a5", "#a39975", "#9b9dad"][props.region]),
);
const shape = computed(() =>
  props.evolved ? info.value?.evolution?.shape : info.value?.shape,
);
</script>
<template>
  <svg
    viewBox="0 0 160 150"
    aria-hidden="true"
    :class="{ hurt, casting, asleep, evolved }"
    class="creature"
  >
    <ellipse cx="80" cy="133" rx="49" ry="9" fill="#254d33" opacity=".12" />
    <g class="body" :style="{ '--fur': color }">
      <g
        v-if="kind === 'guardian'"
        fill="var(--fur)"
        stroke="#345440"
        stroke-width="3"
      >
        <path
          d="M30 96 22 58 42 20 62 40 81 14 103 37 126 23 139 64 130 113 80 135Z"
        />
        <path d="m31 89 24-26 25 11 25-10 27 24-20 31H50Z" fill="#e7edcc" />
        <path
          v-if="region === 0"
          d="m22 78 15-22-17-15M125 52l17-24m-43 10 1-27"
          fill="none"
          stroke="#476e43"
          stroke-width="8"
        />
        <path
          v-if="region === 1"
          d="m39 57 40-20 44 25-39 18Z"
          fill="#e0f2dc"
        />
        <path
          v-if="region === 2"
          d="m53 29 12 27 16-21 22 24"
          fill="none"
          stroke="#e9c179"
          stroke-width="7"
        />
        <path
          v-if="region === 3"
          d="m48 41 31 10 34-15-22 33-17-2Z"
          fill="#eeeacb"
        />
      </g>
      <g v-else-if="kind === 'healer'">
        <path
          d="M80 22c30 30 39 41 35 66-4 31-57 43-71 9-9-25 20-43 36-75Z"
          fill="#eec77c"
        />
        <path d="M81 55c19 19 18 41-1 47-26-5-17-30 1-47Z" fill="#fff8d6" />
      </g>
      <g
        v-else
        fill="var(--fur)"
        stroke="#46614a"
        stroke-width="2.5"
        stroke-linejoin="round"
      >
        <g v-if="shape === 'wings'" class="wings">
          <path d="M57 69C21 22 3 48 26 72 2 69 12 112 56 109Z" />
          <path d="M104 69c35-47 53-21 31 3 24-3 14 40-31 37Z" />
          <path
            d="m32 56 18 23m-18 10 15 9m81-42-18 23m18 10-15 9"
            stroke="#ecedd6"
            stroke-width="6"
          />
        </g>
        <g v-if="shape === 'fins'">
          <path d="m48 66-39 27 34 24 21-22m42-29 44 27-34 24-21-22" />
          <path d="m69 36 12-22 12 24" />
        </g>
        <path
          v-if="shape === 'tail'"
          d="M112 93c44-44 47-13 29 24-9 19-32 12-48 4"
          :stroke-width="evolved ? 9 : 3"
        />
        <path
          v-if="evolved && shape === 'tail'"
          d="M117 94c20-77 54-60 32-18m-39 35c34 15 51 1 35-11"
          fill="none"
          stroke-width="12"
        />
        <g v-if="shape === 'ears'">
          <path d="M40 57 32 20q26 2 34 28m32-2 27-25-1 43" />
        </g>
        <g
          v-if="shape === 'horn'"
          fill="none"
          stroke="#70875a"
          stroke-width="7"
        >
          <path d="m58 47-10-26m4 10-16-5m60 21 10-28m-5 15 18-6" />
        </g>
        <path d="M39 75c-1-37 79-52 87-4l5 27c9 45-105 46-100 0Z" />
        <path
          v-if="shape === 'shell'"
          d="M27 74C25 6 132 15 135 74q-53 18-108 0Z"
          :fill="kind === 'bud' ? '#7f9f71' : '#779382'"
        />
        <g v-if="shape === 'shell'" fill="#e2e8ba" stroke="none">
          <circle cx="53" cy="55" r="8" />
          <circle cx="93" cy="44" r="9" />
          <circle cx="117" cy="61" r="5" />
        </g>
        <ellipse
          cx="80"
          cy="104"
          rx="29"
          ry="21"
          fill="#f0efd5"
          stroke="none"
        />
        <ellipse cx="49" cy="124" rx="14" ry="8" />
        <ellipse cx="110" cy="124" rx="14" ry="8" />
        <path
          v-if="evolved"
          d="m55 39 3-19 15 12 10-20 11 20 15-12 2 20Z"
          fill="#e3d386"
        />
        <path
          v-if="kind === 'sprig' || kind === 'moss'"
          d="M77 42q-20-20-30-5 11 15 30 5 12-25 27-14-4 17-27 14Z"
          fill="#618452"
        />
      </g>
      <g fill="#2e4d3a" stroke="#2e4d3a" stroke-linecap="round">
        <g v-if="asleep" fill="none" stroke-width="3">
          <path d="m57 86 10 0m27 0 10 0" />
        </g>
        <g v-else>
          <ellipse cx="62" cy="85" rx="3.3" ry="5" />
          <ellipse cx="100" cy="85" rx="3.3" ry="5" />
          <circle cx="63" cy="83" r="1" fill="white" />
          <circle cx="101" cy="83" r="1" fill="white" />
        </g>
        <path d="m74 97q7 7 14-1" fill="none" stroke-width="2.3" />
      </g>
      <g fill="#d5a590" opacity=".6">
        <ellipse cx="51" cy="97" rx="7" ry="4" />
        <ellipse cx="112" cy="97" rx="7" ry="4" />
      </g>
      <g v-if="evolved" fill="#ead683">
        <path
          d="m16 24 3 8 8 3-8 3-3 8-3-8-8-3 8-3Zm128 12 2 6 6 2-6 2-2 6-2-6-6-2 6-2Z"
        />
      </g>
    </g>
  </svg>
</template>
<style scoped>
.creature {
  width: 100%;
  height: 100%;
  overflow: visible;
}
.body {
  transform-origin: 80px 126px;
  animation: bob 3s ease-in-out infinite;
}
.hurt .body {
  animation: hit 0.22s ease-out;
}
.casting .body {
  animation: charge 0.3s alternate infinite;
}
.asleep {
  opacity: 0.48;
}
.asleep .body {
  animation: none;
}
.evolved {
  filter: drop-shadow(0 0 5px #e4d9a850);
}
@keyframes bob {
  50% {
    transform: translateY(-3px) rotate(1deg);
  }
}
@keyframes hit {
  20% {
    transform: translateX(7px);
    filter: brightness(1.5);
  }
  70% {
    transform: translateX(-3px);
  }
}
@keyframes charge {
  to {
    transform: scale(1.045);
    filter: brightness(1.2);
  }
}
@media (prefers-reduced-motion: reduce) {
  .body,
  .casting .body {
    animation: none;
  }
}
</style>
