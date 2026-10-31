"""A0: the smallest useful API. Three operations on one resource collection:

    GET  /orders        list orders
    POST /orders        create an order -> 201 Created + Location
    GET  /orders/{id}   read one order  -> 200, or 404 if it doesn't exist

Run:  uv run uvicorn app.main:app --port 8000
"""

from fastapi import FastAPI, Response
from fastapi.responses import JSONResponse

from .models import NewOrder, Order, OrderList
from .store import OrderStore

app = FastAPI(title="Orders API (A0)")
store = OrderStore()


@app.get("/orders", response_model=OrderList)
def list_orders() -> OrderList:
    return OrderList(data=store.all())


@app.post("/orders", status_code=201, response_model=Order)
def create_order(body: NewOrder, response: Response) -> Order:
    order = store.create(body)
    response.headers["Location"] = f"/orders/{order.id}"
    return order


@app.get("/orders/{order_id}", response_model=Order)
def get_order(order_id: str):
    order = store.find(order_id)
    if order is None:
        return JSONResponse(status_code=404, content={"error": f"order {order_id} not found"})
    return order
