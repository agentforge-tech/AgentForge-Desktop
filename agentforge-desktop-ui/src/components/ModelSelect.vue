<template>
  <el-select
    :value="value"
    class="model-select"
    size="mini"
    placeholder="选择模型"
    :disabled="disabled"
    @input="$emit('input', $event)"
  >
    <el-option
      v-for="model in models"
      :key="model.id"
      :label="model.name + ' · ' + model.modelName"
      :value="model.id"
    />
  </el-select>
</template>

<script>
export default {
  name: 'ModelSelect',
  props: {
    value: { type: [Number, String], default: null },
    models: { type: Array, default: () => [] },
    disabled: { type: Boolean, default: false }
  }
}
</script>
