.. _ds_cumulsum:

==============
Cumulative Sum
==============

Setup
-----

Establish a connection to the ATK REST Server.
This handle will be used for the remainder of the script.

Get the server URL and credentials file from the TAP administrator.

.. code::

   atk_server_uri = os.getenv("ATK_SERVER_URI", ia.server.uri)
   credentials_file = os.getenv("ATK_CREDENTIALS", "")

Set the server, and use the credentials to connect to the ATK REST server.

.. code::

   ia.server.uri = atk_server_uri
   ia.connect(credentials_file)

--------
Workflow
--------


The general workflow will build a frame, then run some analytics on the frame.



Build a Frame
-------------

Construct a frame to be uploaded, this is done using Python lists uploaded to the server.
The following frame could represent some ordered list (such as customer orders) and a value associated with the order.
The frame is sorted by the order column, and then the order value is accumulated.

Cumulative sum finds the sum up to and including a given order.

Describe the frame to be built.

.. code::

        rows_frame = ia.UploadRows([[0,100],
                                    [3,20],
                                    [1,25],
                                    [2,90]],
                                   [("order", ia.int32),
                                    ("value", ia.int32)])

Build the frame described in in the UploadRows object.

.. code::

        frame = ia.Frame(rows_frame)

        print frame.inspect()

Operate on the Frame
--------------------

Sort the frame by the order columns, note this is a side effect based operation.

.. code::

        frame.sort('order')

Calculate the cumulative sum.

.. code:: 

       frame.cumulative_sum('value')
        
        print frame.inspect()

Fetch the results, and validate they are what you would expect.

.. code::

        result = frame.take(frame.row_count)
