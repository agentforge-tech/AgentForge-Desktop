<template>
  <div class="page models-page">
    <header class="page-header">
      <div>
        <h1>模型配置</h1>
        <p>模型配置保存在本地 config.json，保存后可直接在工作台选择。</p>
      </div>
      <el-button type="primary" icon="el-icon-plus" @click="openCreate">新增模型</el-button>
    </header>

    <el-table v-loading="loading" :data="models" class="data-table" empty-text="暂无模型">
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="厂商" width="110">
        <template slot-scope="{ row }">{{ providerName(row.provider) }}</template>
      </el-table-column>
      <el-table-column prop="modelName" label="模型标识" min-width="160" />
      <el-table-column prop="url" label="Base URL" min-width="220" show-overflow-tooltip />
      <el-table-column label="操作" width="220" align="right">
        <template slot-scope="{ row }">
          <el-button type="text" @click="test(row)">测试</el-button>
          <el-button type="text" @click="openEdit(row)">编辑</el-button>
          <el-button type="text" class="danger-text" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :title="form.id ? '编辑模型' : '新增模型'" :visible.sync="dialogVisible" width="520px">
      <el-form label-position="top" size="small">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="例如 DeepSeek Flash" />
        </el-form-item>
        <el-form-item label="厂商">
          <el-select v-model="form.provider" style="width: 100%">
            <el-option label="OpenAI（含 OpenAI-compatible）" :value="1" />
            <el-option label="Anthropic" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型标识">
          <el-input v-model="form.modelName" placeholder="deepseek-flash / claude-sonnet-4" />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.url" placeholder="https://tokenrhythm.studio/v1" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" show-password placeholder="留空则沿用已有密钥" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { fetchModels, upsertModel, deleteModel, testConnectivity } from '../../api/model'

const emptyForm = () => ({
  id: null,
  name: '',
  provider: 1,
  modelType: 0,
  modelName: '',
  url: '',
  apiKey: ''
})

export default {
  name: 'ModelsPage',
  data: () => ({
    models: [],
    loading: false,
    saving: false,
    dialogVisible: false,
    form: emptyForm()
  }),
  created() {
    this.load()
  },
  methods: {
    providerName(provider) {
      return provider === 2 ? 'Anthropic' : 'OpenAI'
    },
    async load() {
      this.loading = true
      try {
        this.models = (await fetchModels()) || []
      } catch (error) {
        this.$message.error(error.message || '模型加载失败')
      } finally {
        this.loading = false
      }
    },
    openCreate() {
      this.form = emptyForm()
      this.dialogVisible = true
    },
    openEdit(row) {
      this.form = { ...row, apiKey: '' }
      this.dialogVisible = true
    },
    async save() {
      this.saving = true
      try {
        await upsertModel(this.form)
        this.$message.success('模型已保存')
        this.dialogVisible = false
        await this.load()
      } catch (error) {
        this.$message.error(error.message || '保存失败')
      } finally {
        this.saving = false
      }
    },
    async remove(row) {
      try {
        await this.$confirm(`确定删除模型「${row.name}」吗？`, '删除模型', { type: 'warning' })
      } catch (_error) {
        return
      }
      try {
        await deleteModel(row.id)
        this.$message.success('已删除')
        await this.load()
      } catch (error) {
        this.$message.error(error.message || '删除失败')
      }
    },
    async test(row) {
      try {
        const result = await testConnectivity({ modelId: row.id })
        if (result.success) this.$message.success(result.message)
        else this.$message.warning(result.message)
      } catch (error) {
        this.$message.error(error.message || '测试失败')
      }
    }
  }
}
</script>
