package com.openminis.app.forge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ForgeSkillsStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { temporary.newFolder("forge") }
    private val bundle = linkedMapOf<String, ByteArray>()
    private fun fixture(): ForgeSkillsStore {
        val content = "---\nname: sample\ndescription: test\n---\n正文 😀 支持文件"
        bundle["experts/skills/sample/SKILL.md"] = content.toByteArray()
        bundle["experts/skills/sample/references/guide.md"] = "参考资料".toByteArray()
        val files = JSONArray(bundle.map { (path, bytes) -> JSONObject().put("path", path).put("bytes", bytes.size).put("sha256", ForgeLocalFiles.digest(bytes)) })
        val resources = JSONArray(bundle.map { (path, bytes) -> JSONObject().put("path", path.removePrefix("experts/skills/sample/")).put("bytes", bytes.size).put("sha256", ForgeLocalFiles.digest(bytes)) })
        bundle["catalog.json"] = JSONObject().put("schemaVersion", 1).put("source", JSONObject().put("repository", "test"))
            .put("inventory", JSONObject().put("packs", 1).put("skills", 1).put("files", 2).put("resourceFiles", 1))
            .put("packs", JSONArray().put(JSONObject().put("id", "experts").put("name", "专家").put("count", 1)))
            .put("files", files).put("skills", JSONArray().put(JSONObject().put("id", "sample").put("directory", "experts/skills/sample")
                .put("pack", "experts").put("name", "示例").put("description", "测试技能").put("license", "MIT")
                .put("source", JSONObject()).put("compatibility", JSONObject().put("detail", "Test"))
                .put("files", resources).put("fileCount", 2))).toString().toByteArray()
        return ForgeSkillsStore(root) { bundle.getValue(it) }
    }
    private fun call(store: ForgeSkillsStore, method: String, args: String = "{}", session: String? = "chat-a") = store.request(method, JSONObject(args), session)
    private fun skill(list: JSONObject) = list.getJSONArray("skills").getJSONObject(0)
    private fun rejects(message: String, block: () -> Unit) {
        try { block(); fail("Expected failure: $message") } catch (error: IllegalArgumentException) { assertTrue(error.message, error.message!!.contains(message)) }
    }

    @Test fun defaultsAreOffPreviewIsAllowedButModelReadsCannotBypassEnablement() {
        val store = fixture()
        assertFalse(skill(call(store, "skills_list")).getBoolean("enabled"))
        assertEquals(0, call(store, "skill_list").getJSONArray("skills").length())
        assertNull(store.promptFragment("chat-a"))
        assertTrue(call(store, "skills_read", "{id:sample,requireEnabled:false}").getString("content").contains("正文"))
        rejects("disabled") { call(store, "skill_read", "{id:sample,requireEnabled:false}") }
        rejects("Unsupported skill agent") { call(store, "skills_list", "{agent:codex}") }
    }

    @Test fun globalAndSessionOverridesEnterPromptAndNullRestoresInheritance() {
        val store = fixture()
        call(store, "skills_set", "{id:sample,enabled:true,scope:global,expectedRevision:0}")
        assertTrue(store.promptFragment("chat-a")!!.contains("skill_read"))
        assertTrue(store.promptFragment("chat-b")!!.contains("sample"))
        call(store, "skills_set", "{id:sample,enabled:false,scope:session,threadId:'chat-a',expectedRevision:1}")
        assertNull(store.promptFragment("chat-a"))
        assertNotNull(store.promptFragment("chat-b"))
        rejects("disabled") { call(store, "skill_files", "{id:sample}") }
        rejects("Revision conflict") { call(store, "skills_set", "{id:sample,enabled:true,expectedRevision:0}") }
        call(store, "skills_set", "{id:sample,enabled:null,scope:session}")
        assertTrue(skill(call(store, "skills_list", "{scope:session}")).getBoolean("inherited"))
        assertNotNull(store.promptFragment("chat-a"))
        val reopened = ForgeSkillsStore(root) { bundle.getValue(it) }
        assertNotNull(reopened.promptFragment("chat-a"))
    }

    @Test fun draftSessionEnablementMigratesAndCannotBeForged() {
        val store = fixture()
        call(store, "skills_set", "{id:sample,enabled:true,scope:session}", "__new__draft")
        rejects("does not match") { call(store, "skills_set", "{id:sample,enabled:true,scope:session,threadId:other}") }
        rejects("Open a conversation") { call(store, "skills_set", "{id:sample,enabled:true,scope:session}", null) }
        store.renameSessionOverrides("__new__draft", "real")
        assertNull(store.promptFragment("__new__draft"))
        assertNotNull(store.promptFragment("real"))
        store.clearSessionOverrides("real")
        assertNull(store.promptFragment("real"))
    }

    @Test fun supportingFilesAreManifestBoundVerifiedAndPaged() {
        val store = fixture()
        call(store, "skills_set", "{id:sample,enabled:true}")
        assertEquals(2, call(store, "skill_files", "{id:sample}").getJSONArray("files").length())
        val preview = call(store, "skill_read", "{id:sample,path:'references/guide.md',limit:2}")
        assertEquals("参考", preview.getString("content"))
        assertEquals(2, preview.getInt("nextOffset"))
        assertEquals("资料", call(store, "skill_read", "{id:sample,path:'references/guide.md',offset:2,limit:2}").getString("content"))
        for (path in listOf("../SKILL.md", "/SKILL.md", "x//y", "C:/file", "unknown.md")) {
            try { store.request("skill_read", JSONObject().put("id", "sample").put("path", path), "chat-a"); fail(path) } catch (_: IllegalArgumentException) { }
        }
        rejects("Invalid limit") { call(store, "skill_read", "{id:sample,limit:100001}") }
        File(root, "skills/bundle/experts/skills/sample/references/guide.md").writeText("改写")
        rejects("integrity") { call(store, "skill_read", "{id:sample,path:'references/guide.md'}") }
    }

    @Test fun completeAndroidAssetsMatchAllFourPacksAndEveryDigest() {
        val base = listOf("src/main/assets/forge/bundled-skills", "app/src/main/assets/forge/bundled-skills", "src/android/app/src/main/assets/forge/bundled-skills")
            .map(::File).firstOrNull { File(it, "catalog.json").isFile } ?: error("Run from app, Android, or repository root")
        val store = ForgeSkillsStore(root) { File(base, it).readBytes() }
        val verified = call(store, "skills_verify")
        assertEquals(4, verified.getInt("packs"))
        assertEquals(37, verified.getInt("skills"))
        assertEquals(283, verified.getInt("files"))
        assertEquals(37, call(store, "skills_list").getJSONArray("skills").length())
        assertEquals(0, call(store, "skill_list").getJSONArray("skills").length())
    }
}
