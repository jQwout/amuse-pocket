package com.aiopenpocket.agent.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Покрывает [stripToJsonObject] — чистку «грязного» вывода LLM до голого JSON-объекта. */
class ExtractUserProfileUseCaseTest {

  @Test
  fun `bare json object is returned as is`() {
    val raw = """{"name":"Аня"}"""
    assertEquals("""{"name":"Аня"}""", stripToJsonObject(raw))
  }

  @Test
  fun `json fenced with json tag is unwrapped`() {
    val raw = "```json\n{\"name\":\"Аня\"}\n```"
    assertEquals("""{"name":"Аня"}""", stripToJsonObject(raw))
  }

  @Test
  fun `json fenced without language tag is unwrapped`() {
    val raw = "```\n{\"age\":\"30\"}\n```"
    assertEquals("""{"age":"30"}""", stripToJsonObject(raw))
  }

  @Test
  fun `surrounding chatter is stripped down to the object`() {
    val raw = "Вот ваш профиль: {\"name\":\"Аня\"} Готово!"
    assertEquals("""{"name":"Аня"}""", stripToJsonObject(raw))
  }

  @Test
  fun `nested braces keep the outermost object intact`() {
    // lastIndexOf('}') должен взять закрывающую скобку ВНЕШНЕГО объекта, а не вложенного.
    val raw = "болтовня {\"a\":\"1\",\"b\":{\"c\":\"2\"}} ещё болтовня"
    assertEquals("""{"a":"1","b":{"c":"2"}}""", stripToJsonObject(raw))
  }

  @Test
  fun `leading and trailing whitespace is trimmed`() {
    val raw = "   \n  {\"name\":\"Аня\"}  \n "
    assertEquals("""{"name":"Аня"}""", stripToJsonObject(raw))
  }

  @Test
  fun `trailing prose with extra braces keeps only the first balanced object`() {
    // Наивный lastIndexOf('}') захватил бы и хвостовые скобки «{это}» → битый JSON.
    val raw = "Профиль: {\"name\":\"Иван\"}. Надеюсь {это} поможет!"
    assertEquals("""{"name":"Иван"}""", stripToJsonObject(raw))
  }

  @Test
  fun `braces inside string values are ignored`() {
    val raw = """{"extraInfo":"любит }{ символы"}"""
    assertEquals(raw, stripToJsonObject(raw))
  }

  @Test
  fun `text without any braces yields null`() {
    assertNull(stripToJsonObject("извините, не понял запрос"))
  }

  @Test
  fun `blank input yields null`() {
    assertNull(stripToJsonObject("   "))
  }
}
