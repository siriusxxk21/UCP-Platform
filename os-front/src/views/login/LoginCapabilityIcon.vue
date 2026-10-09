<script setup lang="ts">
defineProps<{
  type: 'collaboration' | 'delivery' | 'intelligence' | 'governance'
}>()
</script>

<template>
  <span class="capability-icon" aria-hidden="true">
    <svg v-if="type === 'collaboration'" viewBox="0 0 48 48">
      <circle cx="20" cy="16" r="6" />
      <path d="M9 37v-3c0-6 5-10 11-10s11 4 11 10v3" />
      <circle cx="33" cy="19" r="4.5" />
      <path d="M31 27c5 0 9 3.5 9 8.5V37" />
    </svg>
    <svg v-else-if="type === 'delivery'" viewBox="0 0 48 48">
      <path d="m18 13-10 11 10 11M30 13l10 11-10 11M27 9l-6 30" />
    </svg>
    <svg v-else-if="type === 'intelligence'" viewBox="0 0 48 48">
      <path d="M18 11a6 6 0 0 0-7 6c0 1 .2 2 .7 2.8A7 7 0 0 0 13 33a6 6 0 0 0 10 3V13a5 5 0 0 0-5-2Z" />
      <path d="M30 11a6 6 0 0 1 7 6c0 1-.2 2-.7 2.8A7 7 0 0 1 35 33a6 6 0 0 1-10 3V13a5 5 0 0 1 5-2Z" />
      <path d="M16 20c3 0 5 2 5 5M32 20c-3 0-5 2-5 5M16 31c2-2 4-2 7-1M32 31c-2-2-4-2-7-1" />
    </svg>
    <svg v-else viewBox="0 0 48 48">
      <path d="M24 7c5 4 10 5 15 6v9c0 9-6 15-15 20-9-5-15-11-15-20v-9c5-1 10-2 15-6Z" />
      <path d="m17 24 5 5 10-11" />
    </svg>
  </span>
</template>

<style scoped>
.capability-icon {
  width: 58px;
  height: 58px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #806bff;
  background: rgba(25, 22, 75, 0.46);
  border: 1px solid rgba(122, 99, 255, 0.72);
  border-radius: 11px;
  box-shadow: 0 0 18px rgba(86, 61, 218, 0.08) inset;
}

svg {
  width: 38px;
  height: 38px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
