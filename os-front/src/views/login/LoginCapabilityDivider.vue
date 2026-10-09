<template>
  <svg class="capability-divider" viewBox="0 0 860 200" preserveAspectRatio="none" aria-hidden="true">
    <defs>
      <linearGradient id="cap-horizontal" gradientUnits="userSpaceOnUse" x1="0" y1="100" x2="860" y2="100">
        <stop offset="0" stop-color="#755cff" stop-opacity=".54" />
        <stop offset=".14" stop-color="#755cff" stop-opacity=".5" />
        <stop offset=".82" stop-color="#755cff" stop-opacity=".46" />
        <stop offset="1" stop-color="#755cff" stop-opacity=".18" />
      </linearGradient>
      <linearGradient id="cap-vertical" gradientUnits="userSpaceOnUse" x1="418" y1="0" x2="418" y2="200">
        <stop offset="0" stop-color="#755cff" stop-opacity=".58" />
        <stop offset=".5" stop-color="#755cff" stop-opacity=".5" />
        <stop offset="1" stop-color="#755cff" stop-opacity=".32" />
      </linearGradient>
      <filter id="node-glow" x="-300%" y="-300%" width="700%" height="700%">
        <feGaussianBlur stdDeviation="4" />
      </filter>
    </defs>
    <path d="M0 100H860" fill="none" stroke="url(#cap-horizontal)" stroke-width="1" />
    <path d="M418 0V200" fill="none" stroke="url(#cap-vertical)" stroke-width="1" />
    <circle cx="418" cy="100" r="8" fill="#6e55ff" opacity=".22" filter="url(#node-glow)" />
    <circle class="divider-node" cx="418" cy="100" r="4" fill="#765cff" />
  </svg>
</template>

<style scoped>
.capability-divider {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: visible;
  pointer-events: none;
}

.divider-node {
  transform-origin: 418px 100px;
  animation: divider-pulse 4.8s ease-in-out infinite;
}

@keyframes divider-pulse {
  0%, 100% { opacity: 0.82; transform: scale(0.92); }
  50% { opacity: 1; transform: scale(1.13); }
}

@media (prefers-reduced-motion: reduce) {
  .divider-node { animation: none; }
}
</style>
