package com.dreamkeeper.llm.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** tag.v1 的 JSON 契约：3–6 个、name 1–8 字、kind 白名单、evidence 必须在原文里。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagExtractorTest {

    private val source = "前天我做了一个梦，梦中好像要去澳门玩，得坐一个类似于宇宙飞船的东西。" +
        "那时候特别激动。跟家里人一起出门，走到一个店门口，店门口的人说不允许你带。"

    @Test
    fun valid_five_tags_parse() {
        val raw = """
        {"tags":[
          {"name":"澳门","kind":"PLACE","evidence":"去澳门"},
          {"name":"宇宙飞船","kind":"OBJECT","evidence":"宇宙飞船"},
          {"name":"家人","kind":"PERSON","evidence":"家里人"},
          {"name":"安检","kind":"ACTION","evidence":"不允许你带"},
          {"name":"激动","kind":"EMOTION","evidence":"特别激动"}
        ]}
        """
        val result = TagExtractor.parse(raw, source) as TagExtractor.Result.Ok
        assertEquals(5, result.drafts.size)
        assertEquals("PLACE", result.drafts.first().kind)
    }

    @Test
    fun code_fence_is_stripped() {
        val raw = "```json\n{\"tags\":[" +
            "{\"name\":\"澳门\",\"kind\":\"PLACE\",\"evidence\":\"去澳门\"}," +
            "{\"name\":\"家人\",\"kind\":\"PERSON\",\"evidence\":\"家里人\"}," +
            "{\"name\":\"店门口\",\"kind\":\"PLACE\",\"evidence\":\"店门口\"}]}\n```"
        assertTrue(TagExtractor.parse(raw, source) is TagExtractor.Result.Ok)
    }

    @Test
    fun fewer_than_three_is_rejected() {
        val raw = "{\"tags\":[{\"name\":\"澳门\",\"kind\":\"PLACE\",\"evidence\":\"去澳门\"}]}"
        assertTrue(TagExtractor.parse(raw, source) is TagExtractor.Result.Bad)
    }

    @Test
    fun unknown_kind_is_rejected() {
        val raw = "{\"tags\":[" +
            "{\"name\":\"澳门\",\"kind\":\"PLANET\",\"evidence\":\"去澳门\"}," +
            "{\"name\":\"家人\",\"kind\":\"PERSON\",\"evidence\":\"家里人\"}," +
            "{\"name\":\"店门口\",\"kind\":\"PLACE\",\"evidence\":\"店门口\"}]}"
        assertTrue(TagExtractor.parse(raw, source) is TagExtractor.Result.Bad)
    }

    @Test
    fun evidence_must_appear_in_source() {
        val raw = "{\"tags\":[" +
            "{\"name\":\"澳门\",\"kind\":\"PLACE\",\"evidence\":\"去澳门\"}," +
            "{\"name\":\"家人\",\"kind\":\"PERSON\",\"evidence\":\"家里人\"}," +
            "{\"name\":\"海边\",\"kind\":\"PLACE\",\"evidence\":\"去了海边\"}]}"
        assertTrue(TagExtractor.parse(raw, source) is TagExtractor.Result.Bad)
    }

    @Test
    fun duplicate_names_collapse_and_can_fail_count() {
        val raw = "{\"tags\":[" +
            "{\"name\":\"澳门\",\"kind\":\"PLACE\",\"evidence\":\"去澳门\"}," +
            "{\"name\":\"澳门\",\"kind\":\"PLACE\",\"evidence\":\"去澳门\"}," +
            "{\"name\":\"家人\",\"kind\":\"PERSON\",\"evidence\":\"家里人\"}]}"
        assertTrue(TagExtractor.parse(raw, source) is TagExtractor.Result.Bad)
    }

    @Test
    fun too_long_name_is_rejected() {
        val raw = "{\"tags\":[" +
            "{\"name\":\"一个特别特别长的标签名字\",\"kind\":\"OTHER\",\"evidence\":\"去澳门\"}," +
            "{\"name\":\"家人\",\"kind\":\"PERSON\",\"evidence\":\"家里人\"}," +
            "{\"name\":\"店门口\",\"kind\":\"PLACE\",\"evidence\":\"店门口\"}]}"
        assertTrue(TagExtractor.parse(raw, source) is TagExtractor.Result.Bad)
    }
}
