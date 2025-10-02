"""Helper script to create a TwelveLabs index using the locally downloaded SDK."""

import os
import uuid

from twelvelabs import TwelveLabs
from twelvelabs.indexes import IndexesCreateRequestModelsItem


def create_index() -> None:
    api_key = os.getenv("API_KEY")
    if not api_key:
        raise RuntimeError(
            "Missing API_KEY environment variable. Set it before running the script."
        )

    with TwelveLabs(api_key=api_key) as client:
        index = client.indexes.create(
            index_name=f"idx-{uuid.uuid4()}",
            models=[
                IndexesCreateRequestModelsItem(
                    model_name="marengo2.7", model_options=["visual", "audio"]
                ),
                IndexesCreateRequestModelsItem(
                    model_name="pegasus1.2", model_options=["visual", "audio"]
                ),
            ],
            addons=["thumbnail"],
        )
        print(f"Created index: id={index.id}")

        retrieved = client.indexes.retrieve(index_id=index.id)
        print(f"Retrieved index: id={retrieved.id} name={retrieved.index_name}")

        updated_name = f"idx-{uuid.uuid4()}"
        client.indexes.update(index_id=index.id, index_name=updated_name)

        updated = client.indexes.retrieve(index_id=index.id)
        print(f"Updated index name to {updated.index_name}")

        print("All indexes registered to the account:")
        for entry in client.indexes.list():
            print(
                f"  id={entry.id} name={entry.index_name} created_at={entry.created_at}"
            )


if __name__ == "__main__":
    create_index()
