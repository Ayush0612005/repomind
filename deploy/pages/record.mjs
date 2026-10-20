// Records real responses from a running RepoMind into deploy/pages/demo-data.json, which the web UI
// replays when it is served as a static page (GitHub Pages). Needs the demo stack up and bootstrapped:
//   node deploy/pages/record.mjs http://localhost:8088
import { writeFileSync } from "node:fs";

const BASE = process.argv[2] ?? "http://localhost:8088";
const OUT = new URL("./demo-data.json", import.meta.url);

// what visitors can click in the recorded demo; questions must match the UI's suggestions
const DEMO = {
  "spring-projects/spring-petclinic": {
    questions: [
      "How is a new owner validated before it is saved?",
      "Where are vets and their specialties loaded?",
      "How does the app find owners by last name?",
      "What happens when a pet's birth date is in the future?",
    ],
    searches: ["find owners by last name", "pet birth date validation", "schedule a visit", "findByLastName"],
    classes: ["OwnerController", "PetValidator", "VetController", "Owner"],
  },
  "Ayush0612005/repomind": {
    questions: [
      "How does hybrid search merge vector and keyword results?",
      "How is a review comment prevented from targeting lines outside the diff?",
      "How does the rate limiter work?",
      "When does the chat gateway fall back to Ollama?",
    ],
    searches: ["reciprocal rank fusion", "token bucket rate limit", "circuit breaker fallback", "verify webhook signature"],
    classes: ["HybridSearch", "AnswerCache", "ChatGateway", "RateLimiter"],
  },
};
const MODES = ["HYBRID", "VECTOR", "KEYWORD"];

// an API key (REPOMIND_KEY) lifts the anonymous rate limit while recording
const KEY = process.env.REPOMIND_KEY;

async function get(path, init = {}) {
  const headers = { ...(init.headers ?? {}), ...(KEY ? { "X-Api-Key": KEY } : {}) };
  const res = await fetch(BASE + path, { ...init, headers });
  if (!res.ok) throw new Error(`${init?.method ?? "GET"} ${path} -> ${res.status} ${await res.text()}`);
  return res.json();
}

const repos = (await get("/api/repos")).filter((r) => r.status === "READY" && DEMO[r.fullName]);
const data = { recordedAt: new Date().toISOString(), repos, answers: {}, searches: {}, classes: {} };

for (const repo of repos) {
  const demo = DEMO[repo.fullName];
  data.answers[repo.id] = {};
  for (const question of demo.questions) {
    const answer = await get(`/api/repos/${repo.id}/ask`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ question }),
    });
    data.answers[repo.id][question] = answer;
    console.log(`${repo.fullName} ask: ${question} -> ${answer.cache?.status ?? "MISS"}, ${answer.citations.length} citations`);
  }
  data.searches[repo.id] = {};
  for (const query of demo.searches) {
    data.searches[repo.id][query] = {};
    for (const mode of MODES) {
      const q = encodeURIComponent(query);
      data.searches[repo.id][query][mode] = await get(`/api/repos/${repo.id}/search?q=${q}&limit=8&mode=${mode}`);
    }
    console.log(`${repo.fullName} search: ${query}`);
  }
  data.classes[repo.id] = {};
  for (const name of demo.classes) {
    data.classes[repo.id][name] = await get(`/api/repos/${repo.id}/classes/${encodeURIComponent(name)}`);
    console.log(`${repo.fullName} class: ${name} (${data.classes[repo.id][name].length} types)`);
  }
}

writeFileSync(OUT, JSON.stringify(data));
console.log(`wrote ${OUT.pathname}`);
