package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"time"
	"unicode/utf8"
)

var errUpstream = errors.New("upstream unavailable or invalid response")

func upstreamClient() *http.Client {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.ResponseHeaderTimeout = 12 * time.Second
	transport.MaxResponseHeaderBytes = 64 << 10
	return &http.Client{
		Transport: transport,
		Timeout:   15 * time.Second,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
}

func fetchJSON(client *http.Client, req *http.Request, limit int64, dst any) error {
	resp, err := client.Do(req)
	if err != nil {
		return errUpstream
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return errUpstream
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil || int64(len(data)) > limit || !utf8.Valid(data) || json.Unmarshal(data, dst) != nil {
		return errUpstream
	}
	return nil
}

func decodeExact(data []byte, dst any) error {
	if !utf8.Valid(data) {
		return errUpstream
	}
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if err := d.Decode(dst); err != nil {
		return errUpstream
	}
	if d.Decode(new(any)) != io.EOF {
		return errUpstream
	}
	return nil
}

func postJSON(ctx context.Context, endpoint string, payload any) (*http.Request, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, errUpstream
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return nil, errUpstream
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	return req, nil
}
