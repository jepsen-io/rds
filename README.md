# jepsen.rds

Automates provisioning of AWS RDS databases for Jepsen tests.

## Usage

See `jepsen.rds/create-postgres!` and `jepsen.rds/teardown!`

<b>WARNING: THIS LIBRARY WILL DESTROY RDS RESOURCES IT DID NOT CREATE. THIS MAY CAUSE DATA LOSS. RUN THIS IN AN ISOLATED ACCOUNT.</b>

## License

Copyright © 2024 Jepsen, LLC

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
