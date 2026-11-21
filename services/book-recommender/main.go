package main

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"io"
	"log"
	"mime"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf8"
)

const recommendationPath = "/v1/recommendations"
const requestTimeout = 45 * time.Second
const maxRequestBytes = 8192

// The process-wide spend gate limits admitted requests, including failed or
// cancelled upstream requests. A host should ALSO rate-limit at ingress.
type quota struct {
	mu          sync.Mutex
	last        time.Time
	tokens      float64
	rate, burst float64 // tokens/second and maximum tokens
}

func (q *quota) take(now time.Time) bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.last.IsZero() {
		q.tokens = q.burst
	} else {
		q.tokens = min(q.burst, q.tokens+max(0, now.Sub(q.last).Seconds())*q.rate)
	}
	q.last = now
	if q.tokens < 1 {
		return false
	}
	q.tokens--
	return true
}

type service struct {
	router router
	search bookSearch
	token  string
	slots  chan struct{}
	quota  *quota
}

func (s service) authorized(r *http.Request) bool {
	if s.token == "" {
		return true
	}
	values := r.Header.Values("Authorization")
	if len(values) != 1 {
		return false
	}
	given, ok := strings.CutPrefix(values[0], "Bearer ")
	if !ok {
		return false
	}
	a, b := sha256.Sum256([]byte(given)), sha256.Sum256([]byte(s.token))
	return subtle.ConstantTimeCompare(a[:], b[:]) == 1
}

func (s service) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	fail := func(status int, code string) {
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(struct {
			Error string `json:"error"`
		}{code})
	}
	ok := func(books []Book) {
		if books == nil {
			books = []Book{}
		}
		_ = json.NewEncoder(w).Encode(struct {
			Books []Book `json:"books"`
		}{books})
	}
	if r.URL.EscapedPath() != recommendationPath {
		fail(http.StatusNotFound, "not_found")
		return
	}
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		fail(http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	if !s.authorized(r) {
		w.Header().Set("WWW-Authenticate", "Bearer")
		fail(http.StatusUnauthorized, "unauthorized")
		return
	}
	contentType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || contentType != "application/json" {
		fail(http.StatusUnsupportedMediaType, "unsupported_media_type")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxRequestBytes))
	var req struct {
		Prompt string `json:"prompt"`
	}
	if err != nil || decodeExact(body, &req) != nil || strings.TrimSpace(req.Prompt) == "" ||
		strings.ContainsRune(req.Prompt, utf8.RuneError) || utf8.RuneCountInString(req.Prompt) > maxPrompt {
		fail(http.StatusBadRequest, "invalid_request")
		return
	}
	if !screen(req.Prompt) {
		ok(nil)
		return
	}
	busy := func() {
		w.Header().Set("Retry-After", "10")
		fail(http.StatusTooManyRequests, "busy")
	}
	select {
	case s.slots <- struct{}{}:
		defer func() { <-s.slots }()
	default:
		busy()
		return
	}
	if !s.quota.take(time.Now()) {
		busy()
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), requestTimeout)
	defer cancel()
	plan, err := s.router.classify(ctx, req.Prompt)
	if err == nil {
		if plan.Want == 0 {
			ok(nil)
			return
		}
		var books []Book
		books, err = s.router.selectBooks(ctx, plan, s.search)
		if err == nil {
			ok(books)
			return
		}
	}
	fail(http.StatusServiceUnavailable, "service_unavailable")
}

func envDefault(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

func main() {
	key := os.Getenv("OPENROUTER_API_KEY")
	if key == "" {
		log.Fatal("OPENROUTER_API_KEY must be provisioned as an authorized server-side secret")
	}
	contact := os.Getenv("OPEN_LIBRARY_CONTACT")
	if contact == "" || len(contact) > 200 || strings.ContainsAny(contact, "\r\n") {
		log.Fatal("OPEN_LIBRARY_CONTACT must be an operator contact email or URL")
	}
	token := os.Getenv("WAVE_BOOK_RECS_TOKEN")
	listen := envDefault("WAVE_BOOK_RECS_LISTEN", "127.0.0.1:8080")
	if token == "" && os.Getenv("WAVE_BOOK_RECS_PRIVATE_INGRESS") != "true" {
		log.Fatal("set WAVE_BOOK_RECS_TOKEN (at least 32 random characters), or WAVE_BOOK_RECS_PRIVATE_INGRESS=true behind private/authenticating ingress")
	}
	if token != "" && (len(token) < 32 || len(token) > 256 || strings.ContainsAny(token, " \t\r\n")) {
		log.Fatal("WAVE_BOOK_RECS_TOKEN must contain 32–256 non-whitespace characters")
	}
	perMinute, err := strconv.Atoi(envDefault("WAVE_BOOK_RECS_REQUESTS_PER_MINUTE", "12"))
	if err != nil || perMinute < 1 || perMinute > 600 {
		log.Fatal("WAVE_BOOK_RECS_REQUESTS_PER_MINUTE must be an integer from 1 to 600")
	}
	client := upstreamClient()
	pace := time.NewTicker(time.Second)
	defer pace.Stop()
	lib := catalog{client: client, userAgent: "WaveBookRecommender/1.0 (" + contact + ")", pace: pace.C}
	httpServer := &http.Server{
		Addr: listen,
		Handler: service{
			router: router{client, key, envDefault("OPENROUTER_MODEL", defaultModel)},
			search: lib.search,
			token:  token,
			slots:  make(chan struct{}, 4),
			quota:  &quota{rate: float64(perMinute) / 60, burst: float64(min(4, perMinute))},
		},
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    8192,
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	serverError := make(chan error, 1)
	go func() { serverError <- httpServer.ListenAndServe() }()
	select {
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), requestTimeout)
		defer cancel()
		_ = httpServer.Shutdown(shutdown)
	case <-serverError:
		log.Fatal("could not start or run the recommendation listener")
	}
}
