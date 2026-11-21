<template>
  <div class="page models-page">
    <header class="page-header">
      <div>
        <h1>模型配置</h1>
        <p>模型配置保存在 conf/models.json，保存后可直接在工作台选择。</p>
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
      <el-table-column label="操作" width="260" align="right">
        <template slot-scope="{ row }">
          <el-button type="text" @click="test(row)">测试</el-button>
          <el-button type="text" @click="openCopy(row)">复制</el-button>
          <el-button type="text" @click="openEdit(row)">编辑</el-button>
          <el-button type="text" class="danger-text" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :title="dialogTitle" :visible.sync="dialogVisible" width="520px">
      <el-form label-position="top" size="small">
        <div v-if="copyFromName" class="model-copy-tip">
          已复制「{{ copyFromName }}」的配置，保存后将创建为新模型。
        </div>
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
          <el-input v-model="form.apiKey" show-password placeholder="请输入 API Key" />
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
    copyFromName: '',
    form: emptyForm()
  }),
  computed: {
    dialogTitle() {
      if (this.copyFromName) return `复制模型 · ${this.form.name}`
      return this.form.id ? '编辑模型' : '新增模型'
    }
  },
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
      this.copyFromName = ''
      this.form = emptyForm()
      this.dialogVisible = true
    },
    openEdit(row) {
      this.copyFromName = ''
      this.form = { ...row }
      this.dialogVisible = true
    },
    openCopy(row) {
      this.copyFromName = row.name || '未命名模型'
      this.form = {
        ...row,
        id: null,
        name: this.nextCopyName(row.name)
      }
      this.dialogVisible = true
    },
    nextCopyName(name) {
      const sourceName = String(name || '未命名模型')
        .replace(/\s*[（(]复制(?:\s*\d+)?[）)]$/, '')
        .trim() || '未命名模型'
      const usedNames = new Set(this.models.map((model) => model.name))
      let candidate = `${sourceName}（复制）`
      let index = 2
      while (usedNames.has(candidate)) {
        candidate = `${sourceName}（复制 ${index}）`
        index += 1
      }
      return candidate
    },
    async save() {
      this.saving = true
      try {
        await upsertModel(this.form)
        this.$message.success(this.copyFromName ? '模型副本已创建' : '模型已保存')
        this.dialogVisible = false
        this.copyFromName = ''
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
