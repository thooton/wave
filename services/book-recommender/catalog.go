package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

const openLibraryURL = "https://openlibrary.org/search.json"
const resultsPerSearch = 24

// Book is the entire public record. It is constructed only from the catalog.
type Book struct {
	ISBN   string `json:"isbn"`
	Title  string `json:"title"`
	Author string `json:"author"`
}

type candidate struct {
	Book
	work string
}

type catalog struct {
	client    *http.Client
	userAgent string
	pace      <-chan time.Time
}

var workKey = regexp.MustCompile(`^/works/OL[0-9]+W$`)

func (c catalog) search(ctx context.Context, terms string) ([]candidate, error) {
	// Shared across requests. Even at max concurrency each process starts at
	// most one Open Library search per configured tick.
	select {
	case <-ctx.Done():
		return nil, errUpstream
	case <-c.pace:
	}
	q := url.Values{
		"q":      {terms},
		"limit":  {strconv.Itoa(resultsPerSearch)},
		"fields": {"key,title,author_name,isbn"},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, openLibraryURL+"?"+q.Encode(), nil)
	if err != nil {
		return nil, errUpstream
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", c.userAgent)
	// Open Library lists ISBNs by work, potentially from multiple editions.
	// The title/author are for the work; an ISBN is an edition linked to it.
	var out struct {
		Docs *[]json.RawMessage `json:"docs"`
	}
	if err := fetchJSON(c.client, req, 2<<20, &out); err != nil || out.Docs == nil {
		return nil, errUpstream
	}
	var found []candidate
	for i, item := range *out.Docs {
		if i == resultsPerSearch {
			break
		}
		var doc struct {
			Key     string   `json:"key"`
			Title   string   `json:"title"`
			Authors []string `json:"author_name"`
			ISBNs   []string `json:"isbn"`
		}
		if json.Unmarshal(item, &doc) != nil || !workKey.MatchString(doc.Key) {
			continue
		}
		title := catalogText(doc.Title, 240)
		var authors []string
		for _, name := range doc.Authors {
			if author := catalogText(name, 120); author != "" {
				authors = append(authors, author)
			}
			if len(authors) == 3 {
				break
			}
		}
		if title == "" || len(authors) == 0 {
			continue
		}
		var best string
		for _, raw := range doc.ISBNs {
			if isbn := isbn13(raw); isbn != "" && (best == "" || isbn < best) {
				best = isbn
			}
		}
		if best != "" {
			found = append(found, candidate{Book{best, title, strings.Join(authors, ", ")}, doc.Key})
		}
	}
	return found, nil
}

func catalogText(s string, limit int) string {
	// Reject non-printing/bidirectional formatting characters and malformed
	// UTF-8 replacement runes instead of letting them steer visual output.
	for _, r := range s {
		if r == utf8.RuneError || unicode.IsControl(r) || unicode.Is(unicode.Cf, r) ||
			unicode.Is(unicode.Zl, r) || unicode.Is(unicode.Zp, r) {
			return ""
		}
	}
	s = strings.Join(strings.Fields(s), " ")
	if utf8.RuneCountInString(s) > limit {
		return ""
	}
	return s
}

func isbn13(raw string) string {
	s := strings.ReplaceAll(strings.ReplaceAll(raw, "-", ""), " ", "")
	sum := 0
	if len(s) == 10 {
		for i, b := range []byte(s) {
			if i == 9 && (b == 'X' || b == 'x') {
				sum += 10
			} else if b >= '0' && b <= '9' {
				sum += (10 - i) * int(b-'0')
			} else {
				return ""
			}
		}
		if sum%11 != 0 || s == "0000000000" {
			return ""
		}
		s = "978" + s[:9]
		sum = 0
		for i, b := range []byte(s) {
			sum += (1 + 2*(i%2)) * int(b-'0')
		}
		return s + string(byte('0'+(10-sum%10)%10))
	}
	if len(s) != 13 || !(strings.HasPrefix(s, "978") || strings.HasPrefix(s, "979") && !strings.HasPrefix(s, "9790")) {
		return ""
	}
	for i, b := range []byte(s) {
		if b < '0' || b > '9' {
			return ""
		}
		sum += (1 + 2*(i%2)) * int(b-'0')
	}
	if sum%10 != 0 {
		return ""
	}
	return s
}

func sortBooks(books []Book) {
	// Neither model ordering nor tool call order determines display order.
	sort.Slice(books, func(i, j int) bool {
		a, b := strings.ToLower(books[i].Title), strings.ToLower(books[j].Title)
		if a == b {
			return books[i].ISBN < books[j].ISBN
		}
		return a < b
	})
}
