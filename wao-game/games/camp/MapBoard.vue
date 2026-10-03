<script setup lang="ts">
import { computed } from "vue";
import { bosses, enemies, maps, towerCatalog, type Point } from "./catalog";
import { stats } from "./engine";
import type { Battle, CampRun, Foe } from "./model";
const props = defineProps<{
  run: CampRun;
  battle: Battle | null;
  selected: number;
  aiming: boolean;
}>();
const emit = defineEmits<{ select: [pad: number]; aim: [point: Point] }>();
const map = computed(() => maps[props.run.map]!);
const selectedTower = computed(() =>
  props.run.towers.find((t) => t.pad === props.selected),
);
const foeName = (f: Foe) =>
  f.boss
    ? bosses[f.kind as keyof typeof bosses].name
    : enemies[f.kind as keyof typeof enemies].name;
const foeColor = (f: Foe) =>
  f.boss
    ? bosses[f.kind as keyof typeof bosses].color
    : enemies[f.kind as keyof typeof enemies].color;
function clickMap(e: MouseEvent) {
  if (!props.aiming) return;
  const svg = e.currentTarget as SVGSVGElement,
    point = svg.createSVGPoint();
  point.x = e.clientX;
  point.y = e.clientY;
  const matrix = svg.getScreenCTM();
  if (matrix) {
    const v = point.matrixTransform(matrix.inverse());
    emit("aim", { x: v.x, y: v.y });
  }
}
</script>
<template>
  <svg
    class="map-board"
    :class="{ aiming }"
    viewBox="0 0 900 560"
    role="img"
    :aria-label="map.name + '防线沙盘'"
    @click="clickMap"
  >
    <defs>
      <pattern id="grid" width="44" height="44" patternUnits="userSpaceOnUse">
        <path
          d="M44 0H0V44"
          fill="none"
          stroke="#7eb4b0"
          stroke-opacity=".14"
        />
      </pattern>
      <filter id="soft"><feGaussianBlur stdDeviation="3" /></filter>
    </defs>
    <rect x="4" y="4" width="892" height="550" rx="35" fill="#a8c5c1" />
    <rect x="4" y="0" width="892" height="540" rx="35" :fill="map.color" />
    <rect x="4" y="0" width="892" height="540" rx="35" fill="url(#grid)" />
    <g opacity=".55" fill="#80b6af">
      <path d="M40 36 60 66 22 66Z" />
      <path d="M740 466 761 501 719 501Z" />
      <path d="M842 462 870 506 818 506Z" />
      <ellipse cx="580" cy="501" rx="54" ry="15" fill="#f0ecd1" />
      <circle cx="778" cy="63" r="11" fill="#d8efde" />
    </g>
    <g
      v-for="(path, i) in map.paths"
      :key="i"
      fill="none"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <polyline
        :points="path.map((p) => `${p.x},${p.y + 6}`).join(' ')"
        stroke="#6ba3a6"
        stroke-width="34"
      />
      <polyline
        :points="path.map((p) => `${p.x},${p.y}`).join(' ')"
        :stroke="i ? '#e0ebe0' : '#eff0d7'"
        stroke-width="28"
      />
      <polyline
        :points="path.map((p) => `${p.x},${p.y}`).join(' ')"
        stroke="#91b7ad"
        stroke-width="2"
        stroke-dasharray="5 17"
      />
      <text :x="35" :y="path[0]!.y - 24" font-size="17" fill="#32616a">
        入口 {{ i + 1 }} →
      </text>
    </g>
    <g :transform="`translate(${map.base.x},${map.base.y})`">
      <ellipse cy="17" rx="30" ry="15" fill="#35636933" />
      <path
        d="M-25 9 0-31 26 9V24H-25Z"
        fill="#fae4af"
        stroke="#557e77"
        stroke-width="3"
      />
      <path d="M-8 24V1H8V24" fill="#3a7075" />
      <path
        d="M0-31V-55L25-48 0-41"
        fill="#e9a163"
        stroke="#557e77"
        stroke-width="2"
      />
      <text y="46" text-anchor="middle" font-size="17" fill="#355f62">
        营地
      </text>
    </g>
    <circle
      v-if="selectedTower"
      :cx="map.pads[selected]!.x"
      :cy="map.pads[selected]!.y"
      :r="stats(selectedTower).range"
      fill="#ffffff17"
      stroke="#397b87"
      stroke-dasharray="7 7"
      stroke-width="2"
      pointer-events="none"
    />
    <g
      v-for="(p, i) in map.pads"
      :key="i"
      :transform="`translate(${p.x},${p.y})`"
      class="pad"
      role="button"
      :aria-label="`选择建造点 ${i + 1}`"
      tabindex="0"
      @click.stop="aiming ? emit('aim', p) : emit('select', i)"
      @keydown.enter.prevent="aiming ? emit('aim', p) : emit('select', i)"
      @keydown.space.prevent="aiming ? emit('aim', p) : emit('select', i)"
    >
      <ellipse
        cy="9"
        rx="30"
        ry="17"
        :fill="selected === i ? '#357889' : '#5d929077'"
      />
      <ellipse
        rx="28"
        ry="17"
        :fill="selected === i ? '#e6f5ef' : '#d4e6de'"
        stroke="#74a3a1"
        stroke-width="2"
      />
      <text
        v-if="!run.towers.some((t) => t.pad === i)"
        y="5"
        text-anchor="middle"
        font-size="18"
        fill="#467d82"
      >
        {{ i + 1 }}
      </text>
    </g>
    <g
      v-for="t in run.towers"
      :key="t.pad"
      :transform="`translate(${map.pads[t.pad]!.x},${map.pads[t.pad]!.y})`"
      pointer-events="none"
      :opacity="battle?.turrets[t.pad]?.disabled ? '.42' : '1'"
    >
      <path
        d="M-21-4 0-15 21-4V10L0 20-21 9Z"
        fill="#628f94"
        stroke="#497a7f"
        stroke-width="2"
      />
      <path d="M-21-4 0 8 21-4 0-15Z" fill="#c7e0d7" />
      <g
        v-if="t.kind === 'bolt'"
        :fill="towerCatalog[t.kind].color"
        stroke="#5d7372"
        stroke-width="2"
      >
        <path d="M-9 1V-28H9V1Z" />
        <path d="M-25-23 0-35 25-23-25-23Z" />
        <path d="M0-31V-48" stroke-width="6" />
      </g>
      <g
        v-else-if="t.kind === 'cannon'"
        :fill="towerCatalog[t.kind].color"
        stroke="#646a6a"
        stroke-width="2"
      >
        <ellipse cy="-9" rx="21" ry="14" />
        <path d="M-9-5-19-34-5-42 12-14Z" />
        <ellipse cx="-12" cy="-37" rx="9" ry="6" fill="#596f73" />
      </g>
      <g v-else-if="t.kind === 'frost'" stroke="#518d9d" stroke-width="2">
        <path d="M-8 3V-25H8V3Z" fill="#eef1d8" />
        <path d="M0-52 15-31 0-12-15-31Z" fill="#b3f0e8" />
        <path d="M0-52V-12M-15-31H15" fill="none" />
      </g>
      <g v-else-if="t.kind === 'beam'" stroke="#75699e" stroke-width="3">
        <path d="M-8 2V-32H8V2Z" fill="#c8b9dd" />
        <path d="M0-50 14-28 0-19-14-28Z" fill="#f3e7bf" />
        <path d="M-8-35 8-49" />
      </g>
      <g v-else stroke="#6e8e67" stroke-width="2">
        <path d="M-7 2V-44H7V2Z" fill="#d9bd8f" />
        <path d="M0-49 25-39 0-30Z" fill="#f6e9ac" />
        <circle cy="-18" r="13" fill="#c8e1ac" />
        <path d="M-6-18H6M0-24V-12" stroke-width="3" />
      </g>
      <g v-if="t.level > 1" fill="#fff4b5">
        <circle
          v-for="n in t.level - 1"
          :key="n"
          :cx="(n - 1.5) * 12"
          cy="24"
          r="4"
        />
      </g>
      <text y="44" text-anchor="middle" fill="#375e68" font-size="16">
        {{ t.pad + 1 }} · {{ towerCatalog[t.kind].short }}{{ t.level }}
      </text>
      <text
        v-if="battle?.turrets[t.pad]?.disabled"
        x="20"
        y="-35"
        font-size="22"
        fill="#805394"
      >
        ⚡
      </text>
    </g>
    <g
      v-for="f in battle?.foes ?? []"
      :key="f.id"
      :transform="`translate(${f.x},${f.y})`"
      class="foe"
    >
      <title>
        {{ foeName(f) }} · {{ Math.ceil(f.hp) }}生命{{
          f.shield ? " · 护盾" + Math.ceil(f.shield) : ""
        }}
      </title>
      <ellipse cy="12" :rx="f.boss ? 30 : 18" ry="9" fill="#234d6033" />
      <g
        :transform="`scale(${f.boss ? 1.55 : 1}) translate(0,${f.flying ? -13 : 0})`"
      >
        <ellipse
          v-if="f.flying"
          cx="-18"
          cy="-9"
          rx="17"
          ry="8"
          fill="#f9efe8"
          transform="rotate(20)"
        />
        <ellipse
          v-if="f.flying"
          cx="18"
          cy="-9"
          rx="17"
          ry="8"
          fill="#f9efe8"
          transform="rotate(-20)"
        />
        <path
          v-if="f.kind === 'hound'"
          d="M-15-9-21-27-2-15M7-15 18-26 17-6"
          :fill="foeColor(f)"
          stroke="#657371"
          stroke-width="2"
        />
        <rect
          x="-17"
          y="-20"
          width="34"
          height="33"
          :rx="f.kind === 'armor' ? 7 : 15"
          :fill="foeColor(f)"
          stroke="#5d7580"
          stroke-width="2"
        />
        <path
          v-if="f.kind === 'armor' || f.kind === 'bastion'"
          d="M-17-8H17M-14-18 0-26 15-18"
          fill="#b6bdce"
          stroke="#687d8f"
          stroke-width="3"
        />
        <path
          v-if="f.kind === 'healer'"
          d="M-5-5H5M0-10V0"
          stroke="#ffefcc"
          stroke-width="4"
        />
        <path
          v-if="f.kind === 'ward'"
          d="M0-20 12-14 10-1 0 5-10-1-12-14Z"
          fill="#b5e8e6"
        />
        <path
          v-if="f.kind === 'drummer'"
          d="M-15 3H15V14H-15Z"
          fill="#f2d7a0"
          stroke="#946f50"
        />
        <circle cx="-6" cy="-8" r="2.4" fill="#304858" />
        <circle cx="6" cy="-8" r="2.4" fill="#304858" />
        <path d="M-4 1Q0 5 4 1" fill="none" stroke="#40596a" stroke-width="2" />
      </g>
      <ellipse
        v-if="f.shield > 0"
        :rx="f.boss ? 35 : 25"
        :ry="f.boss ? 42 : 29"
        cy="-7"
        fill="#abe6f31a"
        stroke="#edfaff"
        stroke-width="3"
      />
      <circle
        v-if="f.freeze > 0"
        r="28"
        fill="#dcffff55"
        stroke="#f6ffff"
        stroke-width="3"
      />
      <circle
        v-if="f.weak > 0"
        r="25"
        fill="none"
        stroke="#d3a4cb"
        stroke-dasharray="4 3"
        stroke-width="3"
      />
      <g v-if="f.charge > 0">
        <circle
          r="48"
          fill="none"
          stroke="#c96563"
          stroke-width="4"
          stroke-dasharray="8 6"
        />
        <text y="-61" text-anchor="middle" font-size="18" fill="#8c4d53">
          {{ f.charge.toFixed(1) }}秒预警
        </text>
      </g>
      <rect
        :x="f.boss ? -34 : -19"
        :y="f.boss ? -58 : -39"
        :width="f.boss ? 68 : 38"
        height="5"
        rx="2"
        fill="#6c777d"
      />
      <rect
        :x="f.boss ? -34 : -19"
        :y="f.boss ? -58 : -39"
        :width="((f.boss ? 68 : 38) * f.hp) / f.maxHp"
        height="5"
        rx="2"
        fill="#f5e7a6"
      />
    </g>
    <g
      v-for="(e, i) in battle?.effects ?? []"
      :key="i"
      :opacity="Math.min(1, e.life * 6)"
      pointer-events="none"
    >
      <line
        v-if="e.kind === 'shot' || e.kind === 'beam'"
        :x1="e.from.x"
        :y1="e.from.y - 20"
        :x2="e.to.x"
        :y2="e.to.y - 8"
        :stroke="e.kind === 'beam' ? '#eee0fb' : '#fff4b4'"
        :stroke-width="e.kind === 'beam' ? 6 : 3"
      />
      <circle
        v-else
        :cx="e.to.x"
        :cy="e.to.y"
        :r="e.radius * (1 - e.life * 0.6)"
        :fill="
          e.kind === 'frost'
            ? '#c5f6fb33'
            : e.kind === 'heal'
              ? '#ddf9b633'
              : '#eecda133'
        "
        :stroke="
          e.kind === 'frost'
            ? '#edffff'
            : e.kind === 'heal'
              ? '#e6ffc9'
              : e.kind === 'leak'
                ? '#ce6969'
                : '#fce4b2'
        "
        stroke-width="4"
      />
    </g>
  </svg>
</template>
<style scoped>
.map-board {
  display: block;
  width: 100%;
  max-height: 430px;
  outline: none;
}
.pad {
  cursor: pointer;
}
.pad:focus {
  outline: none;
}
.pad:focus ellipse:first-child {
  stroke: #e0a152;
  stroke-width: 5;
}
.aiming {
  cursor: crosshair;
}
.foe {
  pointer-events: none;
}
@media (min-width: 851px) and (max-height: 700px) {
  .map-board {
    height: max(230px, calc(100dvh - 285px));
  }
}
@media (max-width: 650px) {
  .map-board {
    max-height: none;
    width: 100%;
    min-height: 180px;
  }
}
</style>
