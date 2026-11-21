package main

import (
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	maxPrompt   = 1200
	defaultWant = 5
	maxWant     = 20
	maxQueries  = 4
)

// A high-precision first pass, not a general injection or semantic detector.
// Ordinary uses of words such as "code", "spelling", "letters" and "secret"
// in book requests must not be enough by themselves to reject a request.
var forbidden = []*regexp.Regexp{
	regexp.MustCompile(`\b(ignore|disregard|override|forget|bypass) (?:all |any |the |your |these )?(?:previous |prior |original |above |earlier |safety |system |developer )?(instructions|rules|policies|policy|prompt)\b`),
	regexp.MustCompile(`\b(reveal|print|repeat|show|leak|expose|tell me) (?:me )?(?:all |the |your |hidden )?(system prompt|developer instructions|api key|hidden instructions|private instructions)\b`),
	regexp.MustCompile(`\b(acrostic|telestich)\b.{0,48}\b(titles?|initials?|isbn|message|answer|reply)\b|\b(titles?|initials?|isbn|message|answer|reply)\b.{0,48}\b(acrostic|telestich)\b`),
	regexp.MustCompile(`\b(spell(?:s|ing)? out|encod(?:e|es|ing)|signal|communicate|hide|convey)\b.{0,85}\b(titles?|initials?|isbn(?:s)?|book order|result count)\b`),
	regexp.MustCompile(`\b(first|last|nth|first few|last few) (?:character|letter|word|digit)s?\b.{0,75}\b(spell(?:s|ing)?|form(?:s|ing)?|read(?:s|ing)?|encod(?:e|es|ing)|say(?:s|ing)?)\b`),
	regexp.MustCompile(`\b(answer|respond|reply) (?:only )?(?:to .{1,45} )?(?:using|with|through|via|by) (?:only )?(?:the )?(?:real )?(book titles|book recommendations|isbn(?:s)?|authors|books)\b`),
	regexp.MustCompile(`\b(roleplay|pretend|act) (?:that |as )?(?:you are |a |an |the )?(system|developer|different assistant|unrestricted assistant)\b`),
}

func screen(prompt string) bool {
	folded := strings.Map(func(r rune) rune {
		switch {
		case r >= 0xff01 && r <= 0xff5e:
			r -= 0xfee0 // ASCII fullwidth variants
		case unicode.Is(unicode.Cf, r) || unicode.Is(unicode.Mn, r):
			return -1 // discretionary/invisible formatting and combining marks
		}
		if unicode.IsLetter(r) || unicode.IsNumber(r) {
			return unicode.ToLower(r)
		}
		return ' '
	}, prompt)
	folded = strings.Join(strings.Fields(folded), " ")
	for _, re := range forbidden {
		if re.MatchString(folded) {
			return false
		}
	}
	return true
}

// Search is plain bibliographic terms, not raw user instructions, Solr syntax,
// field selectors, arbitrary tool names, or addresses.
func searchTerms(s string) string {
	if utf8.RuneCountInString(s) > 100 || !utf8.ValidString(s) {
		return ""
	}
	terms := strings.FieldsFunc(s, func(r rune) bool {
		return !(unicode.IsLetter(r) || unicode.IsNumber(r) || unicode.IsMark(r))
	})
	if len(terms) > 12 {
		return ""
	}
	return strings.ToLower(strings.Join(terms, " ")) // neutralize uppercase boolean operators
}
