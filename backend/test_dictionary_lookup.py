import unittest
from gateway import Config, ServiceError, Gateway
from dictionary_lookup import lookup
from unittest.mock import patch


class DictionaryTest(unittest.TestCase):
    def setUp(self):
        self.config = Config("test-vendor", "a" * 32, "https://vendor.example/v1")
        self.entry = [{"word": "bank", "phonetics": [{"text": "/bæŋk/"}],
            "license": {"name": "CC BY-SA", "url": "https://example.org/license"},
            "sourceUrls": ["https://example.org/bank"], "meanings": [
                {"partOfSpeech": "noun", "definitions": [{"definition": "River edge.", "example": "Sit by the river."},
                    {"definition": "Financial institution."}]},
                {"partOfSpeech": "verb", "definitions": [{"definition": "Deposit money."}]}]}]

    def translated(self, config, word, senses, timeout):
        return {"senses": [{"id": s["id"], "definitionZh": "中文释义：" + s["definition"]} for s in senses]}

    def test_all_source_senses_grouped_without_inventing_parts_of_speech(self):
        result = lookup(self.config, " bank ", lambda word: self.entry, self.translated)
        self.assertTrue(result["dictionary"])
        self.assertEqual(3, result["senseCount"])
        self.assertEqual(["noun", "verb"], result["partsOfSpeech"])
        self.assertIn("【名词 n.】", result["translatedText"])
        self.assertIn("【动词 v.】", result["translatedText"])
        self.assertNotIn("【形容词", result["translatedText"])
        self.assertIn("Financial institution", result["translatedText"])
        self.assertIn("https://example.org/bank", result["translatedText"])
        self.assertIn("CC BY-SA", result["translatedText"])

    def test_many_senses_batched_without_silent_truncation(self):
        self.entry[0]["meanings"] = [{"partOfSpeech": "verb", "definitions": [
            {"definition": "Sense " + str(i)} for i in range(61)]}]
        calls = []
        def translate(config, word, senses, timeout):
            calls.append(len(senses))
            return self.translated(config, word, senses, timeout)
        result = lookup(self.config, "run", lambda word: self.entry, translate)
        self.assertEqual([13, 24, 24], sorted(calls))
        self.assertEqual(61, result["senseCount"])
        self.assertIn("61. 中文释义：Sense 60", result["translatedText"])

    def test_wrong_missing_duplicate_or_english_only_translations_are_rejected(self):
        for response in ({"senses": []}, {"senses": [None, None, None]},
            {"senses": [{"id": "wrong", "definitionZh": "错误"}]*3},
            {"senses": [{"id": "sense-0", "definitionZh": "错误"}]*3},
            {"senses": [{"id": "sense-"+str(i), "definitionZh": "Untranslated"} for i in range(3)]}):
            with self.assertRaises(ServiceError):
                lookup(self.config, "bank", lambda word: self.entry, lambda *args: response)

    def test_phrase_invalid_direction_and_context_cannot_use_dictionary(self):
        for text in ("hello world", "你好", "x"*65, "../bank"):
            with self.assertRaises(ServiceError):
                lookup(self.config, text, lambda word: self.fail("No dictionary network call expected"))
        gateway = Gateway(self.config, lambda *args: self.fail("No paid translation expected"))
        for extra in ({"source": "zh", "target": "en"}, {"source": "en", "target": "zh", "context": {}}):
            with self.assertRaises(ServiceError):
                gateway._translate(dict(q="bank", profile="dictionary", **extra))

    def test_gateway_reuses_translate_route_and_default_requests_remain_general(self):
        gateway = Gateway(self.config, lambda *args: "普通译文")
        with patch("dictionary_lookup.lookup", return_value={"dictionary": True}) as dictionary:
            self.assertTrue(gateway._translate(dict(q="bank", source="en", target="zh", profile="dictionary"))["dictionary"])
            self.assertEqual("普通译文", gateway._translate(dict(q="hello world", source="en", target="zh"))["translatedText"])
            self.assertEqual(1, dictionary.call_count)

    def test_unknown_word_does_not_fabricate_an_entry(self):
        def missing(word):
            raise ServiceError(422, "词典暂未收录")
        with self.assertRaises(ServiceError) as caught:
            lookup(self.config, "unknown", missing, self.translated)
        self.assertEqual(422, caught.exception.status)
