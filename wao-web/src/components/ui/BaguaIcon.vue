<template>
  <svg viewBox="0 0 32 32" fill="none" aria-hidden="true" focusable="false">
    <g v-for="(lines, index) in ['111', '110', '101', '100', '000', '001', '010', '011']" :key="lines" :transform="`rotate(${index * 45} 16 16)`" stroke="currentColor" stroke-width="1.3">
      <path v-for="(line, row) in lines" :key="row" :d="line === '1' ? `M12 ${2 + row * 2.3}h8` : `M12 ${2 + row * 2.3}h3 M17 ${2 + row * 2.3}h3`" />
    </g>
    <circle cx="16" cy="16" r="5" stroke="currentColor" stroke-width="1.2" />
    <path d="M16 11a2.5 2.5 0 0 1 0 5 2.5 2.5 0 0 0 0 5 5 5 0 0 1 0-10Z" fill="currentColor" />
    <circle cx="16" cy="13.5" r=".8" fill="var(--wao-bg-side)" />
    <circle cx="16" cy="18.5" r=".8" fill="currentColor" />
  </svg>
</template>
