<template>
  <div class="risk-strip">
    <div
      class="risk-level"
      :style="{
        width: '100%',
        backgroundColor: riskColor
      }"
    ></div>

    <div class="risk-labels">
      <span>Low</span>
      <span>Medium</span>
      <span>High</span>
    </div>
  </div>
</template>

<script>
export default {
  name: 'RiskStrip',
  props: {
    riskLevel: {
      type: Number,
      default: 0,
      validator: value => value >= 0 && value <= 100
    }
  },
  computed: {
    riskColor() {
      if (this.riskLevel <= 33) return '#4CAF50'; // Green
      if (this.riskLevel <= 66) return '#FFC107'; // Yellow
      return '#F44336'; // Red
    }
  }
}
</script>

<style scoped>
.risk-strip {
  margin: 1rem 0;
  border-radius: 4px;
  overflow: hidden;
  box-shadow: 0 2px 4px rgba(0,0,0,0.1);
}

.risk-level {
  height: 24px;
  transition: background-color 0.3s ease;
}

.risk-labels {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 0.75rem;
  color: #666;
}
</style>