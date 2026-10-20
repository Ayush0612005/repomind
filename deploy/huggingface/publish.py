"""Publishes the folder built by prepare.sh as a Hugging Face Docker Space.

Set your token first (create one at https://huggingface.co/settings/tokens with "Write" access);
it is read from the environment and never written anywhere:
    PowerShell:  $env:HF_TOKEN = "hf_..."
    then:        py deploy/huggingface/publish.py <your-hf-username>/repomind D:/DevCache/hf-space
"""
import os
import sys

from huggingface_hub import HfApi


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit("usage: publish.py <username>/<space-name> <space-folder>")
    repo_id, folder = sys.argv[1], sys.argv[2]
    if not os.environ.get("HF_TOKEN"):
        sys.exit("Set HF_TOKEN to a Hugging Face token with write access first.")
    api = HfApi()
    api.create_repo(repo_id, repo_type="space", space_sdk="docker", exist_ok=True)
    api.upload_folder(repo_id=repo_id, repo_type="space", folder_path=folder,
                      commit_message="Publish RepoMind demo")
    owner, name = repo_id.split("/", 1)
    print(f"Uploaded. The Space builds for ~15-25 minutes, then runs at:")
    print(f"  https://huggingface.co/spaces/{repo_id}")
    print(f"  https://{owner.lower()}-{name.lower()}.hf.space   (direct app URL)")


if __name__ == "__main__":
    main()
